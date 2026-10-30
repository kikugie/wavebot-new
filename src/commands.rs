use crate::channels;
use crate::data::{ApplicationReference, BotConfig, RuntimeData, Saveable, Snowflake};
use poise::{BoxFuture, Command, CreateReply};
use serenity::all::{
    ActionRowComponent, ChannelId, ComponentInteraction, CreateActionRow, CreateInputText, CreateInteractionResponse,
    CreateInteractionResponseFollowup, CreateModal, FullEvent, Http, InputTextStyle, Interaction, Message,
    ModalInteraction, ModalInteractionCollector,
};
use std::time::Duration;

pub type Error = anyhow::Error;
type Context<'a> = poise::Context<'a, (), Error>;

pub fn all() -> Vec<Command<(), Error>> {
    fn described(mut command: Command<(), Error>, text: &'static str) -> Command<(), Error> {
        command.description = Some(text.into());
        command
    }
    vec![
        open_ticket(),
        deny_ticket(),
        edit_discord(),
        described(accept(), "Promote the applicant to a provisional"),
        described(reject(), "Schedule to remove the current interview channel"),
        described(reload(), "Reload config and storage files"),
        described(query(), "Print applications"),
    ]
}

async fn followup(http: &Http, token: &str, content: impl Into<String>) -> Result<(), Error> {
    http.create_followup_message(
        token,
        &CreateInteractionResponseFollowup::new().content(content).ephemeral(true),
        Vec::new(),
    )
    .await?;
    Ok(())
}

async fn fail(http: &Http, token: &str, message: &str, error: Option<Error>) -> Result<(), Error> {
    match &error {
        Some(e) => error!("{message}\n{e:?}"),
        None => error!("{message}"),
    }
    let reason = error.map_or("Unknown".into(), |e| e.to_string());
    followup(http, token, format!("{message} - {reason}")).await
}

fn edit_modal(id: &str) -> CreateInteractionResponse {
    CreateInteractionResponse::Modal(CreateModal::new(id, "Edit discord").components(vec![CreateActionRow::InputText(
        CreateInputText::new(InputTextStyle::Short, "Edit discord", "content"),
    )]))
}

async fn await_modal(ctx: &serenity::all::Context, id: String) -> Option<(ModalInteraction, Option<String>)> {
    let modal = ModalInteractionCollector::new(&ctx.shard)
        .filter(move |it| it.data.custom_id == id)
        .timeout(Duration::from_secs(15 * 60))
        .await?;
    let value = modal.data.components.iter().flat_map(|row| &row.components).find_map(|it| match it {
        ActionRowComponent::InputText(input) if input.custom_id == "content" => input.value.clone(),
        _ => None,
    });
    Some((modal, value))
}

fn reference(message: Snowflake) -> Option<ApplicationReference> {
    let storage = crate::state().storage.lock().unwrap();
    storage.messages.get(&message).filter(|it| it.to_entry(&storage.applications).is_some()).cloned()
}

fn ticket_in(channel: ChannelId) -> Option<Snowflake> {
    let storage = crate::state().storage.lock().unwrap();
    storage.tickets.iter().find(|(_, it)| it.channel == channel.get()).map(|(id, _)| *id)
}

#[poise::command(context_menu_command = "Open ticket", ephemeral, default_member_permissions = "MANAGE_ROLES")]
async fn open_ticket(ctx: Context<'_>, message: Message) -> Result<(), Error> {
    ctx.defer_ephemeral().await?;
    debug!("Processing message {}", message.id);
    let entry = crate::state().storage.lock().unwrap().entry(message.id.get()).cloned();
    let Some(entry) = entry else {
        ctx.say("Selected message is not an application").await?;
        return Ok(());
    };
    debug!("Creating ticket for {}", entry.discord);
    match channels::ticket(&message, &entry).await {
        Ok(()) => ctx.say("Ticket created").await?,
        Err(e) => {
            error!("Failed to create ticket\n{e:?}");
            ctx.say(format!("Failed to create ticket: {e}")).await?
        }
    };
    Ok(())
}

#[poise::command(context_menu_command = "Deny ticket", ephemeral, default_member_permissions = "MANAGE_ROLES")]
async fn deny_ticket(ctx: Context<'_>, message: Message) -> Result<(), Error> {
    ctx.defer_ephemeral().await?;
    debug!("Processing message {}", message.id);
    let entry = crate::state().storage.lock().unwrap().entry(message.id.get()).cloned();
    let Some(entry) = entry else {
        ctx.say("Selected message is not an application").await?;
        return Ok(());
    };
    match channels::deny(&message, &entry).await {
        Ok(()) => ctx.say("Ticket denied").await?,
        Err(e) => {
            error!("Failed to deny ticket\n{e:?}");
            ctx.say(format!("Failed to deny ticket: {e}")).await?
        }
    };
    Ok(())
}

#[poise::command(context_menu_command = "Edit discord", ephemeral, default_member_permissions = "MANAGE_ROLES")]
async fn edit_discord(ctx: Context<'_>, message: Message) -> Result<(), Error> {
    let poise::Context::Application(app) = ctx else { return Ok(()) };
    let http = ctx.http();
    let id = format!("wavebot/modal/{}", app.interaction.id);
    app.interaction.create_response(http, edit_modal(&id)).await?;
    let Some((modal, value)) = await_modal(ctx.serenity_context(), id).await else { return Ok(()) };
    modal.defer_ephemeral(http).await?;

    let Some(reference) = reference(message.id.get()) else {
        return followup(http, &modal.token, "Selected message is not an application").await;
    };
    let Some(value) = value.filter(|it| !it.is_empty()) else {
        return followup(http, &modal.token, "No content provided").await;
    };
    match channels::edit(&message, &reference, &value).await {
        Ok(()) => followup(http, &modal.token, format!("Changed discord name to {value}")).await,
        Err(e) => {
            followup(http, &modal.token, format!("Failed to change discord name: {e}")).await?;
            error!("Failed to change discord name\n{e:?}");
            Ok(())
        }
    }
}

#[poise::command(slash_command, ephemeral, default_member_permissions = "MANAGE_ROLES")]
async fn accept(ctx: Context<'_>) -> Result<(), Error> {
    ctx.defer_ephemeral().await?;
    let Some(ticket) = ticket_in(ctx.channel_id()) else {
        ctx.say("Must be executed in a ticket channel").await?;
        return Ok(());
    };
    match channels::accept(ticket).await {
        Err(e) => {
            error!("Failed to accept ticket\n{e:?}");
            ctx.say(format!("Failed to accept ticket: {e}")).await?
        }
        Ok(()) => ctx.say("Ticket accepted").await?,
    };
    Ok(())
}

#[poise::command(slash_command, ephemeral, default_member_permissions = "MANAGE_ROLES")]
async fn reject(ctx: Context<'_>) -> Result<(), Error> {
    ctx.defer_ephemeral().await?;
    let Some(ticket) = ticket_in(ctx.channel_id()) else {
        ctx.say("Must be executed in a ticket channel").await?;
        return Ok(());
    };
    match channels::reject(ticket).await {
        Err(e) => {
            error!("Failed to reject ticket\n{e:?}");
            ctx.say(format!("Failed to reject ticket: {e}")).await?
        }
        Ok(()) => ctx.say("Ticket rejected").await?,
    };
    Ok(())
}

#[poise::command(slash_command, ephemeral, default_member_permissions = "MANAGE_ROLES")]
async fn reload(ctx: Context<'_>) -> Result<(), Error> {
    ctx.defer_ephemeral().await?;
    let state = crate::state();
    let config = BotConfig::load()?;
    *state.config.write().unwrap() = config;
    let storage = RuntimeData::load()?;
    *state.storage.lock().unwrap() = storage;
    crate::update().await;
    ctx.say("Configuration reloaded").await?;
    Ok(())
}

#[poise::command(slash_command, default_member_permissions = "MANAGE_ROLES")]
async fn query(ctx: Context<'_>) -> Result<(), Error> {
    ctx.defer().await?;
    let embed = channels::query().await?;
    ctx.send(CreateReply::default().embed(embed)).await?;
    Ok(())
}

pub fn event_handler<'a>(
    framework: poise::FrameworkContext<'a, (), Error>,
    event: &'a FullEvent,
) -> BoxFuture<'a, Result<(), Error>> {
    Box::pin(async move {
        if let FullEvent::InteractionCreate { interaction: Interaction::Component(component) } = event
            && component.data.custom_id.starts_with("wavebot/ticket")
        {
            button(framework.serenity_context, component).await?;
        }
        Ok(())
    })
}

async fn button(ctx: &serenity::all::Context, component: &ComponentInteraction) -> Result<(), Error> {
    let http = &ctx.http;
    let message = &component.message;
    let entry = crate::state().storage.lock().unwrap().entry(message.id.get()).cloned();
    let Some(entry) = entry else {
        component.defer_ephemeral(http).await?;
        return fail(http, &component.token, "Selected message is not an application", None).await;
    };
    match component.data.custom_id.rsplit('/').next().unwrap_or_default() {
        "open" => {
            component.defer_ephemeral(http).await?;
            match channels::ticket(message, &entry).await {
                Ok(()) => followup(http, &component.token, "Successfully opened ticket").await,
                Err(e) => fail(http, &component.token, "Failed to create ticket", Some(e)).await,
            }
        }
        "deny" => {
            component.defer_ephemeral(http).await?;
            match channels::deny(message, &entry).await {
                Ok(()) => followup(http, &component.token, "Successfully denied ticket").await,
                Err(e) => fail(http, &component.token, "Failed to deny ticket", Some(e)).await,
            }
        }
        "edit" => {
            let id = format!("wavebot/modal/{}", component.id);
            component.create_response(http, edit_modal(&id)).await?;
            let Some((modal, value)) = await_modal(ctx, id).await else { return Ok(()) };
            modal.defer_ephemeral(http).await?;
            let (Some(reference), Some(value)) = (reference(message.id.get()), value) else {
                return fail(http, &modal.token, "Failed to get modal response", None).await;
            };
            match channels::edit(message, &reference, &value).await {
                Ok(()) => followup(http, &modal.token, "Successfully edited discord name").await,
                Err(e) => fail(http, &modal.token, "Failed to edit discord name", Some(e)).await,
            }
        }
        other => {
            component.defer_ephemeral(http).await?;
            fail(http, &component.token, &format!("Unknown ticket button interaction: {other}"), None).await
        }
    }
}
