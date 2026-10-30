package dev.kikugie.wavebot.server

import dev.kikugie.wavebot.Main
import dev.kikugie.wavebot.Main.CONFIG
import dev.kikugie.wavebot.Main.GUILD
import dev.kikugie.wavebot.Main.STORAGE
import dev.kikugie.wavebot.sheet.ApplicationData
import dev.kikugie.wavebot.sheet.ApplicationReference
import dev.kikugie.wavebot.sheet.Ticket
import dev.kikugie.wavebot.sheet.TicketState
import dev.kikugie.wavebot.util.referring
import dev.kord.common.entity.ButtonStyle
import dev.kord.common.entity.Permission
import dev.kord.common.entity.Permissions
import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.channel.asChannelOf
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.behavior.channel.edit
import dev.kord.core.behavior.channel.editMemberPermission
import dev.kord.core.behavior.channel.threads.edit
import dev.kord.core.behavior.createTextChannel
import dev.kord.core.behavior.edit
import dev.kord.core.behavior.getChannelOf
import dev.kord.core.behavior.getChannelOfOrNull
import dev.kord.core.entity.Message
import dev.kord.core.entity.channel.TextChannel
import dev.kord.core.entity.channel.VoiceChannel
import dev.kord.core.entity.channel.thread.ThreadChannel
import dev.kord.rest.builder.message.actionRow
import dev.kord.rest.builder.message.embed
import dev.kordex.core.components.components
import dev.kordex.core.types.GenericInteractionContext
import dev.kordex.core.utils.addReaction
import dev.kordex.core.utils.dm
import dev.kordex.core.utils.getJumpUrl
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.time.Clock
import kotlinx.serialization.ExperimentalSerializationApi

@ExperimentalSerializationApi
object ChannelManager {
    private const val CHECKMARK = "✅"
    private const val CROSSMARK = "❌"
    private const val THUMBSUP = "\uD83D\uDC4D"
    private const val THUMBSDOWN = "\uD83D\uDC4E"

    suspend fun preview(entry: ApplicationData) = kotlin.runCatching {
        val channel = GUILD.getChannel(CONFIG.server.previewChannel).asChannelOf<TextChannel>()
        val message = channel.createMessage {
            entry.preview(this)
            components {
                actionRow {
                    interactionButton(ButtonStyle.Success, "wavebot/ticket/open") {
                        label = "Open ticket"
                    }
                    interactionButton(ButtonStyle.Danger, "wavebot/ticket/deny") {
                        label = "Deny ticket"
                    }
                    interactionButton(ButtonStyle.Secondary, "wavebot/ticket/edit") {
                        label = "Edit discord"
                    }
                }
            }
        }.also {
            STORAGE.messages[it.id] = entry.toReference(STORAGE.applications[entry.type.text]!!)
            STORAGE.save()
            it.addReaction(THUMBSUP)
            it.addReaction(THUMBSDOWN)
        }
        channel.startPublicThreadWithMessage(
            message.id,
            "${entry.minecraft.referring()} ${entry.type.text} application"
        ).also {
            entry.contents(it)
        }
    }.onFailure { it.printStackTrace() }

    suspend fun ticket(message: Message, entry: ApplicationData) = kotlin.runCatching {
        message.addReaction(CHECKMARK)
        GUILD.getChannelOf<ThreadChannel>(message.id).edit { archived = true; locked = true }

        val member = checkNotNull(entry.findMember()) { "Member '${entry.discord}' not found" }
        check(STORAGE.tickets[member.id] == null) { "User already has a ticket" }

        val channel = GUILD.createTextChannel("${entry.type.id}-${entry.minecraft}-application") {
            position = Int.MAX_VALUE
            parentId = CONFIG.server.applicationCategory
        }
        channel.createMessage { entry.preview(this) }
        entry.contents(channel)

        val ticket = Ticket(member.id, channel.id, TicketState.OPEN)
        STORAGE.messages.remove(message.id)
        STORAGE.tickets[member.id] = ticket
        STORAGE.save()

        member.addRole(CONFIG.server.applicantRole)
        channel.editMemberPermission(member.id) {
            allowed = Permission.ViewChannel + Permission.SendMessages
        }
        val voice = GUILD.getChannelOfOrNull<VoiceChannel>(CONFIG.server.voiceChannel)
        channel.createMessage {
            content = """
                ## Welcome to your application channel, ${member.mention}!
                You're welcome to share details about your projects and technical experience here, as well as introduce yourself to the server members. During this period you also have access to ${voice?.mention.orEmpty()} if you want to engage with the server. Afterwards, whenever you're accepted or rejected will be determined by the vote among the server members, and you'll be notified about the decision.
            """.trimIndent()
        }
    }

    suspend fun deny(message: Message, entry: ApplicationData) = kotlin.runCatching {
        message.addReaction(CROSSMARK)
        GUILD.getChannelOf<ThreadChannel>(message.id).edit { archived = true; locked = true }

        val member = checkNotNull(entry.findMember()) { "Member '${entry.discord}' not found" }
        check(STORAGE.tickets[member.id] == null) { "User already has a ticket" }

        val ticket = Ticket(member.id, Snowflake(0), TicketState.REJECTED)
        STORAGE.messages.remove(message.id)
        STORAGE.tickets[member.id] = ticket
        STORAGE.save()

        Main.LOGGER.info("Denying #${member.effectiveName}")
        member.asUser().dm {
            content = """
                ## Sorry, your Wavetech application has been rejected.
                Don't be discouraged! You're welcome to apply again when applications reopen. In the meantime, feel free to hone your skills and prepare for another opportunity. Best of luck!
            """.trimIndent()
        }
    }

    suspend fun edit(message: Message, entry: ApplicationData, discord: String) = kotlin.runCatching {
        val member = checkNotNull(ApplicationData.findMember(discord)) { "Member '$discord' not found" }
        check(STORAGE.tickets[member.id] == null) { "User already has a ticket" }

        entry.discord = discord
        message.edit { entry.preview(this) }
        STORAGE.save()
    }

    suspend fun accept(ticket: Ticket) = kotlin.runCatching {
        check(ticket.state == TicketState.OPEN) { "Ticket is not open" }
        val channel = GUILD.getChannelOf<TextChannel>(ticket.channel)
        val member = GUILD.getMember(ticket.member)

        ticket.state = TicketState.ACCEPTED
        STORAGE.save()
        member.removeRole(CONFIG.server.applicantRole)
        member.addRole(CONFIG.server.provisionalRole)
        Main.LOGGER.info("Accepting #${channel.name}")

        channel.createMessage {
            content = """
                ## Welcome to Wavetech, ${member.mention}!
                Your application has been accepted and your provisional period begins. You're able to chat in all member channels and join all Minecraft servers (SMP, CMP, copy, etc.). This can last for a while, and if you fit in the community and are actively playing, you'll be promoted to a permanent member. Have fun!
            """.trimIndent()
        }
        channel.edit {
            parentId = CONFIG.server.archiveCategory
            position = 0
        }
    }

    suspend fun reject(ticket: Ticket) = kotlin.runCatching {
        check(ticket.state == TicketState.OPEN) { "Ticket is not open" }
        val channel = GUILD.getChannelOf<TextChannel>(ticket.channel)
        val member = GUILD.getMemberOrNull(ticket.member)

        ticket.countdown = Clock.System.now()
        ticket.state = TicketState.REJECTED
        STORAGE.save()
        val removal = Clock.System.now() + CONFIG.countdown
        Main.LOGGER.info("Scheduling #${channel.name} for removal in ${CONFIG.countdown}")

        if (member == null)
            channel.createMessage("The application has been rejected, but the applicant has left the server. This channel will be deleted in approximately <t:${removal.epochSeconds}:R>.")
        else {
            member.removeRole(CONFIG.server.applicantRole)
            channel.createMessage {
                content = """
                    ## Thank you for applying, ${member.mention}.
                    Unfortunately, your application has been rejected this time. Don't be discouraged! You're welcome to apply again when applications reopen. In the meantime, feel free to hone your skills and prepare for another opportunity. Best of luck! This channel will be deleted in approximately <t:${removal.epochSeconds}:R>.
                """.trimIndent()
            }
//            channel.editMemberPermission(member.id) {
//                denied = Permissions(Permission.SendMessages)
//                allowed = Permissions(Permission.ViewChannel)
//            }
        }
        channel.edit {
            parentId = CONFIG.server.rejectedCategory
            position = 0
        }
    }

    suspend fun delete(ticket: Ticket) = kotlin.runCatching {
        check(ticket.state == TicketState.REJECTED) { "Ticket is not rejected" }
        GUILD.getChannelOf<TextChannel>(ticket.channel).delete()
    }

    suspend fun query(ctx: GenericInteractionContext) = coroutineScope {
        val channel = GUILD.getChannelOf<TextChannel>(CONFIG.server.previewChannel)
        val pending = STORAGE.messages.map { (msg, ref) ->
            async { isPending(channel, msg, ref) }
        }.awaitAll().filterNotNull()

        val channels = STORAGE.tickets.values.filter { it.state != TicketState.ACCEPTED }.map {
            async { it to GUILD.getChannelOfOrNull<TextChannel>(it.channel) }
        }.awaitAll().mapNotNull { (ticket, channel) ->
            if (channel == null) return@mapNotNull null
            ticket to channel
        }.toMap()

        val active = channels.filter { (it, _) -> it.state == TicketState.OPEN }
        val waiting = channels.filter { (it, _) -> it.state == TicketState.REJECTED }

        ctx.respond {
            embed {
                title = "Applications"
                field("Pending: ${pending.size}") {
                    pending.joinToString("\n") { "- ${it.getJumpUrl()}" }
                }
                field("Active: ${active.size}") {
                    active.values.joinToString("\n") { "- ${it.getJumpUrl()}" }
                }
                field("Rejected: ${waiting.size}") {
                    waiting.values.joinToString("\n") { "- ${it.getJumpUrl()}" }
                }
            }
        }
    }

    private suspend fun isPending(channel: TextChannel, msg: Snowflake, ref: ApplicationReference): Message? =
        channel.getMessageOrNull(msg)?.takeIf { it.reactions.none { it.emoji.name == CHECKMARK || it.emoji.name == CROSSMARK } }
}