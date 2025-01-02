package dev.kikugie.wavebot.server

import dev.kikugie.wavebot.Main.CONFIG
import dev.kikugie.wavebot.Main.GUILD
import dev.kikugie.wavebot.Main.STORAGE
import dev.kikugie.wavebot.i18n.Translations.Wavebot.Extension.Message as Translations
import dev.kikugie.wavebot.sheet.ApplicationData
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
import dev.kord.core.behavior.getChannelOf
import dev.kord.core.behavior.getChannelOfOrNull
import dev.kord.core.entity.Message
import dev.kord.core.entity.channel.TextChannel
import dev.kord.core.entity.channel.VoiceChannel
import dev.kord.core.entity.channel.thread.ThreadChannel
import dev.kord.rest.builder.message.actionRow
import dev.kordex.core.components.components
import dev.kordex.core.utils.addReaction
import dev.kordex.core.utils.dm
import kotlinx.datetime.Clock
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
                    interactionButton(ButtonStyle.Danger, "wavebot/ticket/edit") {
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
                ## ${Translations.Welcome.title.translateNamed("user" to member.mention)}
                ${Translations.Welcome.body.translateNamed("voice" to voice?.mention.orEmpty())}
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

        member.asUser().dm {
            content = """
                ## ${Translations.Deny.title.translate()}
                ${Translations.Deny.body.translate()}
            """.trimIndent()
        }
    }

    suspend fun edit(message: Message, entry: ApplicationData, discord: String) = kotlin.runCatching {
        val member = checkNotNull(GUILD.getMemberOrNull(Snowflake(discord))) { "Member '$discord' not found" }
        check(STORAGE.tickets[member.id] == null) { "User already has a ticket" }

        entry.discord = discord
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
        channel.createMessage {
            content = """
                ## ${Translations.Accept.title.translateNamed("user" to member.mention)}
                ${Translations.Accept.body.translate()}
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
        if (member == null) channel.createMessage(Translations.Reject.empty.translateNamed("time" to "<t:${removal.epochSeconds}:R>"))
        else {
            member.removeRole(CONFIG.server.applicantRole)
            channel.createMessage {
                content = """
                    ## ${Translations.Reject.title.translateNamed("user" to member.mention)}
                    ${Translations.Reject.body.translateNamed("time" to "<t:${removal.epochSeconds}:R>")}
                """.trimIndent()
            }
            channel.editMemberPermission(member.id) {
                denied = Permissions(Permission.SendMessages)
                allowed = Permissions(Permission.ViewChannel)
            }
        }
    }

    suspend fun delete(ticket: Ticket) = kotlin.runCatching {
        check(ticket.state == TicketState.REJECTED) { "Ticket is not rejected" }
        GUILD.getChannelOf<TextChannel>(ticket.channel).delete()
    }
}