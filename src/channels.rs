use crate::application::{ApplicationData, find_member};
use crate::data::{ApplicationReference, Saveable, Snowflake, Ticket, TicketState};
use anyhow::{Context, Result, ensure};
use chrono::Utc;
use futures::future::join_all;
use serenity::all::{
    ButtonStyle, ChannelId, ChannelType, CreateActionRow, CreateButton, CreateChannel, CreateEmbed, CreateMessage,
    CreateThread, EditChannel, EditMessage, EditThread, GuildChannel, Mentionable, Message, MessageId,
    PermissionOverwrite, PermissionOverwriteType, Permissions, ReactionType, RoleId, UserId,
};

const CHECKMARK: &str = "✅";
const CROSSMARK: &str = "❌";
const THUMBSUP: &str = "👍";
const THUMBSDOWN: &str = "👎";

fn reaction(emoji: &str) -> ReactionType {
    ReactionType::Unicode(emoji.into())
}

fn not_found<T>(result: serenity::Result<T>) -> Result<Option<T>> {
    match result {
        Ok(it) => Ok(Some(it)),
        Err(serenity::Error::Http(serenity::all::HttpError::UnsuccessfulRequest(e))) if e.status_code.as_u16() == 404 => Ok(None),
        Err(e) => Err(e.into()),
    }
}

pub async fn guild_channel(id: Snowflake) -> Result<Option<GuildChannel>> {
    if id == 0 {
        return Ok(None);
    }
    let state = crate::state();
    Ok(not_found(ChannelId::new(id).to_channel(&state.http).await)?
        .and_then(|it| it.guild())
        .filter(|it| it.guild_id == state.guild))
}

async fn channel_of(id: Snowflake, kind: ChannelType) -> Result<Option<GuildChannel>> {
    Ok(guild_channel(id).await?.filter(|it| it.kind == kind))
}

async fn text_channel(id: Snowflake) -> Result<GuildChannel> {
    channel_of(id, ChannelType::Text).await?.with_context(|| format!("Text channel {id} not found"))
}

async fn archive_thread(message: &Message) -> Result<()> {
    let http = &crate::state().http;
    ChannelId::new(message.id.get())
        .edit_thread(http, EditThread::new().archived(true).locked(true))
        .await?;
    Ok(())
}

fn check_no_ticket(member: UserId) -> Result<()> {
    let storage = crate::state().storage.lock().unwrap();
    ensure!(!storage.tickets.contains_key(&member.get()), "User already has a ticket");
    Ok(())
}

fn ticket_buttons() -> Vec<CreateActionRow> {
    vec![CreateActionRow::Buttons(vec![
        CreateButton::new("wavebot/ticket/open").style(ButtonStyle::Success).label("Open ticket"),
        CreateButton::new("wavebot/ticket/deny").style(ButtonStyle::Danger).label("Deny ticket"),
        CreateButton::new("wavebot/ticket/edit").style(ButtonStyle::Secondary).label("Edit discord"),
    ])]
}

pub async fn preview(index: usize, entry: &ApplicationData) -> Result<()> {
    let state = crate::state();
    let http = &state.http;
    let preview_channel = state.config.read().unwrap().server.preview_channel;
    let channel = text_channel(preview_channel).await?;
    let message = channel
        .send_message(http, CreateMessage::new().embed(entry.preview()).components(ticket_buttons()))
        .await?;
    {
        let mut storage = state.storage.lock().unwrap();
        storage.messages.insert(
            message.id.get(),
            ApplicationReference { kind: entry.kind.text().into(), index },
        );
        storage.save()?;
    }
    message.react(http, reaction(THUMBSUP)).await?;
    message.react(http, reaction(THUMBSDOWN)).await?;
    let thread = channel
        .id
        .create_thread_from_message(http, message.id, CreateThread::new(entry.title()))
        .await?;
    entry.contents(http, thread.id).await
}

pub async fn ticket(message: &Message, entry: &ApplicationData) -> Result<()> {
    let state = crate::state();
    let http = &state.http;
    message.react(http, reaction(CHECKMARK)).await?;
    archive_thread(message).await?;

    let member = entry.find_member().await?.with_context(|| format!("Member '{}' not found", entry.discord))?;
    check_no_ticket(member.user.id)?;

    let server = state.config.read().unwrap().server.clone();
    let channel = state
        .guild
        .create_channel(
            http,
            CreateChannel::new(format!("{}-{}-application", entry.kind.id(), entry.minecraft))
                .kind(ChannelType::Text)
                .position(u16::MAX)
                .category(ChannelId::new(server.application_category)),
        )
        .await?;
    channel.send_message(http, CreateMessage::new().embed(entry.preview())).await?;
    entry.contents(http, channel.id).await?;

    {
        let mut storage = state.storage.lock().unwrap();
        storage.messages.shift_remove(&message.id.get());
        storage.tickets.insert(
            member.user.id.get(),
            Ticket { member: member.user.id.get(), channel: channel.id.get(), state: TicketState::Open, countdown: None },
        );
        storage.save()?;
    }

    member.add_role(http, RoleId::new(server.applicant_role)).await?;
    channel
        .create_permission(
            http,
            PermissionOverwrite {
                allow: Permissions::VIEW_CHANNEL | Permissions::SEND_MESSAGES,
                deny: Permissions::empty(),
                kind: PermissionOverwriteType::Member(member.user.id),
            },
        )
        .await?;
    let voice = channel_of(server.voice_channel, ChannelType::Voice).await?;
    let content = format!(
        "## Welcome to your application channel, {}!\nYou're welcome to share details about your projects and technical experience here, as well as introduce yourself to the server members. During this period you also have access to {} if you want to engage with the server. Afterwards, whenever you're accepted or rejected will be determined by the vote among the server members, and you'll be notified about the decision.",
        member.mention(),
        voice.map(|it| it.mention().to_string()).unwrap_or_default()
    );
    channel.send_message(http, CreateMessage::new().content(content)).await?;
    Ok(())
}

pub async fn deny(message: &Message, entry: &ApplicationData) -> Result<()> {
    let state = crate::state();
    let http = &state.http;
    message.react(http, reaction(CROSSMARK)).await?;
    archive_thread(message).await?;

    let member = entry.find_member().await?.with_context(|| format!("Member '{}' not found", entry.discord))?;
    check_no_ticket(member.user.id)?;

    {
        let mut storage = state.storage.lock().unwrap();
        storage.messages.shift_remove(&message.id.get());
        storage.tickets.insert(
            member.user.id.get(),
            Ticket { member: member.user.id.get(), channel: 0, state: TicketState::Rejected, countdown: None },
        );
        storage.save()?;
    }

    info!("Denying #{}", member.display_name());
    if let Ok(dm) = member.user.create_dm_channel(http).await {
        dm.send_message(
            http,
            CreateMessage::new().content("## Sorry, your Wavetech application has been rejected.\nDon't be discouraged! You're welcome to apply again when applications reopen. In the meantime, feel free to hone your skills and prepare for another opportunity. Best of luck!"),
        )
        .await?;
    }
    Ok(())
}

pub async fn edit(message: &Message, reference: &ApplicationReference, discord: &str) -> Result<()> {
    let state = crate::state();
    let member = find_member(discord).await?.with_context(|| format!("Member '{discord}' not found"))?;
    check_no_ticket(member.user.id)?;

    let entry = {
        let mut storage = state.storage.lock().unwrap();
        let entry = storage
            .applications
            .get_mut(&reference.kind)
            .and_then(|it| it.get_mut(reference.index))
            .context("Selected message is not an application")?;
        entry.discord = discord.into();
        entry.clone()
    };
    message
        .channel_id
        .edit_message(&state.http, message.id, EditMessage::new().embed(entry.preview()))
        .await?;
    state.storage.lock().unwrap().save()
}

fn ticket_state(member: Snowflake) -> Result<Ticket> {
    crate::state().storage.lock().unwrap().tickets.get(&member).cloned().context("Ticket not found")
}

pub async fn accept(member_id: Snowflake) -> Result<()> {
    let state = crate::state();
    let http = &state.http;
    let ticket = ticket_state(member_id)?;
    ensure!(ticket.state == TicketState::Open, "Ticket is not open");
    let channel = text_channel(ticket.channel).await?;
    let member = state.guild.member(http, UserId::new(ticket.member)).await?;

    {
        let mut storage = state.storage.lock().unwrap();
        if let Some(it) = storage.tickets.get_mut(&member_id) {
            it.state = TicketState::Accepted;
        }
        storage.save()?;
    }
    let server = state.config.read().unwrap().server.clone();
    member.remove_role(http, RoleId::new(server.applicant_role)).await?;
    member.add_role(http, RoleId::new(server.provisional_role)).await?;
    info!("Accepting #{}", channel.name);

    let content = format!(
        "## Welcome to Wavetech, {}!\nYour application has been accepted and your provisional period begins. You're able to chat in all member channels and join all Minecraft servers (SMP, CMP, copy, etc.). This can last for a while, and if you fit in the community and are actively playing, you'll be promoted to a permanent member. Have fun!",
        member.mention()
    );
    channel.send_message(http, CreateMessage::new().content(content)).await?;
    channel
        .id
        .edit(http, EditChannel::new().category(ChannelId::new(server.archive_category)).position(0))
        .await?;
    Ok(())
}

pub async fn reject(member_id: Snowflake) -> Result<()> {
    let state = crate::state();
    let http = &state.http;
    let ticket = ticket_state(member_id)?;
    ensure!(ticket.state == TicketState::Open, "Ticket is not open");
    let channel = text_channel(ticket.channel).await?;
    let member = not_found(state.guild.member(http, UserId::new(ticket.member)).await)?;

    {
        let mut storage = state.storage.lock().unwrap();
        if let Some(it) = storage.tickets.get_mut(&member_id) {
            it.countdown = Some(Utc::now());
            it.state = TicketState::Rejected;
        }
        storage.save()?;
    }
    let (countdown, server) = {
        let config = state.config.read().unwrap();
        (config.countdown, config.server.clone())
    };
    let removal = Utc::now() + countdown.0;
    info!("Scheduling #{} for removal in {}", channel.name, countdown);

    let time = format!("<t:{}:R>", removal.timestamp());
    match member {
        None => {
            let content = format!("The application has been rejected, but the applicant has left the server. This channel will be deleted in approximately {time}.");
            channel.send_message(http, CreateMessage::new().content(content)).await?;
        }
        Some(member) => {
            member.remove_role(http, RoleId::new(server.applicant_role)).await?;
            let content = format!(
                "## Thank you for applying, {}.\nUnfortunately, your application has been rejected this time. Don't be discouraged! You're welcome to apply again when applications reopen. In the meantime, feel free to hone your skills and prepare for another opportunity. Best of luck! This channel will be deleted in approximately {time}.",
                member.mention()
            );
            channel.send_message(http, CreateMessage::new().content(content)).await?;
        }
    }
    channel
        .id
        .edit(http, EditChannel::new().category(ChannelId::new(server.rejected_category)).position(0))
        .await?;
    Ok(())
}

pub async fn delete(ticket: &Ticket) -> Result<()> {
    ensure!(ticket.state == TicketState::Rejected, "Ticket is not rejected");
    text_channel(ticket.channel).await?.delete(&crate::state().http).await?;
    Ok(())
}

async fn is_pending(channel: ChannelId, message: Snowflake) -> Result<Option<Message>> {
    let found = not_found(channel.message(&crate::state().http, MessageId::new(message)).await)?;
    Ok(found.filter(|it| {
        !it.reactions.iter().any(|r| matches!(&r.reaction_type, ReactionType::Unicode(e) if e == CHECKMARK || e == CROSSMARK))
    }))
}

fn listing(urls: impl Iterator<Item = String>) -> String {
    let joined = urls.map(|it| format!("- {it}")).collect::<Vec<_>>().join("\n");
    if joined.is_empty() { "-".into() } else { joined }
}

pub async fn query() -> Result<CreateEmbed> {
    let state = crate::state();
    let preview_channel = state.config.read().unwrap().server.preview_channel;
    let channel = text_channel(preview_channel).await?;
    let (messages, tickets): (Vec<Snowflake>, Vec<Ticket>) = {
        let storage = state.storage.lock().unwrap();
        (
            storage.messages.keys().copied().collect(),
            storage.tickets.values().filter(|it| it.state != TicketState::Accepted).cloned().collect(),
        )
    };

    let pending: Vec<Message> = join_all(messages.into_iter().map(|it| is_pending(channel.id, it)))
        .await
        .into_iter()
        .collect::<Result<Vec<_>>>()?
        .into_iter()
        .flatten()
        .collect();
    let channels: Vec<(Ticket, GuildChannel)> = join_all(tickets.into_iter().map(|it| async move {
        let found = channel_of(it.channel, ChannelType::Text).await?;
        Ok::<_, anyhow::Error>(found.map(|c| (it, c)))
    }))
    .await
    .into_iter()
    .collect::<Result<Vec<_>>>()?
    .into_iter()
    .flatten()
    .collect();

    let jump = |c: &GuildChannel| format!("https://discord.com/channels/{}/{}", c.guild_id, c.id);
    let active: Vec<&GuildChannel> = channels.iter().filter(|(t, _)| t.state == TicketState::Open).map(|(_, c)| c).collect();
    let waiting: Vec<&GuildChannel> = channels.iter().filter(|(t, _)| t.state == TicketState::Rejected).map(|(_, c)| c).collect();

    Ok(CreateEmbed::new()
        .title("Applications")
        .field(
            format!("Pending: {}", pending.len()),
            listing(pending.iter().map(|m| m.id.link(m.channel_id, Some(state.guild)))),
            false,
        )
        .field(format!("Active: {}", active.len()), listing(active.iter().map(|c| jump(c))), false)
        .field(format!("Rejected: {}", waiting.len()), listing(waiting.iter().map(|c| jump(c))), false))
}
