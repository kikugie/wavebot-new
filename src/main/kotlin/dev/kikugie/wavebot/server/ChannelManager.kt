package dev.kikugie.wavebot.server

import dev.kikugie.wavebot.Main.CONFIG
import dev.kikugie.wavebot.Main.GUILD
import dev.kikugie.wavebot.Main.STORAGE
import dev.kikugie.wavebot.i18n.Translations.Wavebot.Extension.Message
import dev.kikugie.wavebot.sheet.ApplicationData
import dev.kikugie.wavebot.sheet.Ticket
import dev.kikugie.wavebot.sheet.TicketState
import dev.kikugie.wavebot.util.referring
import dev.kord.common.entity.Permission
import dev.kord.common.entity.Permissions
import dev.kord.core.behavior.channel.asChannelOf
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.behavior.channel.edit
import dev.kord.core.behavior.channel.editMemberPermission
import dev.kord.core.behavior.createTextChannel
import dev.kord.core.behavior.getChannelOf
import dev.kord.core.behavior.getChannelOfOrNull
import dev.kord.core.entity.channel.TextChannel
import dev.kord.core.entity.channel.VoiceChannel
import dev.kordex.core.utils.addReaction
import kotlinx.datetime.Clock
import kotlinx.serialization.ExperimentalSerializationApi

@ExperimentalSerializationApi
object ChannelManager {
    private const val CHECKMARK = "✅"
    private const val CROSSMARK = "❎"

    suspend fun preview(entry: ApplicationData) = kotlin.runCatching {
        val channel = GUILD.getChannel(CONFIG.server.previewChannel).asChannelOf<TextChannel>()
        val message = entry.preview(channel).also {
            STORAGE.messages[it.id] = entry
            STORAGE.save()
            it.addReaction(CHECKMARK)
            it.addReaction(CROSSMARK)
        }
        channel.startPublicThreadWithMessage(
            message.id,
            "${entry.minecraft.referring()} ${entry.type.text} application"
        ).also {
            entry.contents(it)
        }
    }.onFailure { it.printStackTrace() }

    suspend fun ticket(entry: ApplicationData) = kotlin.runCatching {
        val member = checkNotNull(entry.findMember()) { "Member '${entry.discord}' not found" }
        check(STORAGE.tickets[member.id] == null) { "User already has a ticket" }

        val channel = GUILD.createTextChannel("${entry.type.id}-${entry.minecraft}-application") {
            position = Int.MAX_VALUE
            parentId = CONFIG.server.applicationCategory
        }
        entry.preview(channel)
        entry.contents(channel)

        val ticket = Ticket(member.id, channel.id, TicketState.OPEN)
        STORAGE.tickets[member.id] = ticket
        STORAGE.save()

        member.addRole(CONFIG.server.applicantRole)
        channel.editMemberPermission(member.id) {
            allowed = Permission.ViewChannel + Permission.SendMessages
        }
        val voice = GUILD.getChannelOfOrNull<VoiceChannel>(CONFIG.server.voiceChannel)
        channel.createMessage {
            content = """
                ## ${Message.Welcome.title.translateNamed("user" to member.mention)}
                ${Message.Welcome.body.translateNamed("voice" to voice?.mention.orEmpty())}
            """.trimIndent()
        }
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
                ## ${Message.Accept.title.translateNamed("user" to member.mention)}
                ${Message.Accept.body.translate()}
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
        if (member == null) channel.createMessage(Message.Reject.empty.translateNamed("time" to "<t:${removal.epochSeconds}:R>"))
        else {
            member.removeRole(CONFIG.server.applicantRole)
            channel.createMessage {
                content = """
                    ## ${Message.Reject.title.translateNamed("user" to member.mention)}
                    ${Message.Reject.body.translateNamed("time" to "<t:${removal.epochSeconds}:R>")}
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
        STORAGE.tickets.remove(ticket.member)
    }
}