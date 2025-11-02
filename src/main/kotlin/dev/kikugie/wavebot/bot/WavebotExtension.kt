package dev.kikugie.wavebot.bot

import dev.kikugie.wavebot.Main
import dev.kikugie.wavebot.Main.CONFIG
import dev.kikugie.wavebot.Main.GUILD
import dev.kikugie.wavebot.Main.LOGGER
import dev.kikugie.wavebot.Main.STORAGE
import dev.kikugie.wavebot.load
import dev.kikugie.wavebot.server.BotConfig
import dev.kikugie.wavebot.server.ChannelManager
import dev.kikugie.wavebot.server.RuntimeData
import dev.kord.common.entity.Permission
import dev.kord.core.behavior.interaction.ActionInteractionBehavior
import dev.kord.core.behavior.interaction.response.DeferredEphemeralMessageInteractionResponseBehavior
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.interaction.ButtonInteraction
import dev.kord.core.event.interaction.ButtonInteractionCreateEvent
import dev.kordex.core.components.forms.ModalForm
import dev.kordex.core.events.EventContext
import dev.kordex.core.extensions.Extension
import dev.kordex.core.extensions.ephemeralMessageCommand
import dev.kordex.core.extensions.ephemeralSlashCommand
import dev.kordex.core.extensions.event
import dev.kordex.core.i18n.types.Key
import kotlinx.serialization.ExperimentalSerializationApi
import dev.kikugie.wavebot.i18n.Translations.Wavebot.Extension as Translations

@OptIn(ExperimentalSerializationApi::class)
class WavebotExtension : Extension() {
    override val name: String = "wavebot"

    private suspend fun ActionInteractionBehavior.error(message: String, error: Throwable? = null) =
        deferEphemeralResponse().error(message, error)

    private suspend fun DeferredEphemeralMessageInteractionResponseBehavior.error(
        message: String,
        error: Throwable? = null
    ) {
        if (error != null) LOGGER.error(message, error) else LOGGER.error(message)
        respond { content = "$message - ${error?.message ?: "Unknown"}" }
    }

    private suspend fun ButtonInteraction.buttonAction(context: EventContext<ButtonInteractionCreateEvent>) {
        val entry = STORAGE.entry(message) ?: return error("Selected message is not an application")
        when (componentId.substringAfterLast('/')) {
            "open" -> with(deferEphemeralResponse()) {
                ChannelManager.ticket(message, entry).onSuccess {
                    respond { content = "Successfully opened ticket" }
                }.onFailure {
                    error("Failed to create ticket", it)
                }
            }

            "deny" -> with(deferEphemeralResponse()) {
                ChannelManager.deny(message, entry).onSuccess {
                    respond { content = "Successfully denied ticket" }
                }.onFailure {
                    error("Failed to deny ticket", it)
                }
            }

            "edit" -> EditDiscordForm().sendAndAwait(context) { form ->
                val result = form?.textInputs?.get("content")?.value
                    ?: return@sendAndAwait error("Failed to get modal response")
                val callback = form.deferEphemeralResponse()

                ChannelManager.edit(message, entry, result).onSuccess {
                    callback.respond { content = "Successfully edited discord name" }
                }.onFailure {
                    callback.error("Failed to edit discord name", it)
                }
            }

            else -> error("Unknown ticket button interaction: ${componentId.substringAfterLast('/')}")
        }
    }

    override suspend fun setup() {
        event<ButtonInteractionCreateEvent> {
            check {
                failIf(!event.interaction.componentId.startsWith("wavebot/ticket"))
            }

            action { event.interaction.buttonAction(this@action) }
        }

        ephemeralMessageCommand {
            name = Translations.ticket

            guild(GUILD.id)
            requirePermission(Permission.ManageRoles)
            action {
                targetMessages.forEach { message ->
                    LOGGER.debug("Processing message {}", message.id)
                    val entry = STORAGE.messages[message.id]?.toEntry(STORAGE.applications) ?: run {
                        respond { content = "Selected message is not an application" }
                        return@forEach
                    }
                    LOGGER.debug("Creating ticket for {}", entry.discord)
                    ChannelManager.ticket(message, entry)
                        .onSuccess {
                            respond { content = "Ticket created" }
                        }
                        .onFailure {
                            LOGGER.error("Failed to create ticket", it)
                            respond { content = "Failed to create ticket: ${it.message}" }
                        }
                }
            }
        }
        ephemeralMessageCommand {
            name = Translations.deny

            guild(GUILD.id)
            requirePermission(Permission.ManageRoles)
            action {
                targetMessages.forEach { message ->
                    LOGGER.debug("Processing message {}", message.id)
                    val entry = STORAGE.messages[message.id]?.toEntry(STORAGE.applications) ?: run {
                        respond { content = "Selected message is not an application" }
                        return@forEach
                    }
                    ChannelManager.deny(message, entry)
                        .onSuccess {
                            respond { content = "Ticket denied" }
                        }
                        .onFailure {
                            LOGGER.error("Failed to deny ticket", it)
                            respond { content = "Failed to deny ticket: ${it.message}" }
                        }
                }
            }
        }
        ephemeralMessageCommand(::EditDiscordForm) {
            name = Translations.edit

            guild(GUILD.id)
            requirePermission(Permission.ManageRoles)
            action {
                targetMessages.forEach { message ->
                    val entry = STORAGE.messages[message.id]?.toEntry(STORAGE.applications) ?: run {
                        respond { content = "Selected message is not an application" }
                        return@forEach
                    }
                    val result = it?.content?.value ?: run {
                        respond { content = "No content provided" }
                        return@action
                    }
                    ChannelManager.edit(message, entry, result)
                        .onSuccess {
                            respond { content = "Changed discord name to $result" }
                        }
                        .onFailure {
                            respond { content = "Failed to change discord name: ${it.message}" }
                            LOGGER.error("Failed to change discord name", it)
                        }
                }
            }
        }
        ephemeralSlashCommand {
            name = Key("accept")
            description = Translations.accept

            guild(GUILD.id)
            requirePermission(Permission.ManageRoles)
            action {
                val ticket = STORAGE.tickets.values.find { it.channel == channel.id } ?: run {
                    respond { content = "Must be executed in a ticket channel" }
                    return@action
                }

                ChannelManager.accept(ticket).onFailure {
                    LOGGER.error("Failed to accept ticket", it)
                    respond { content = "Failed to accept ticket: ${it.message}" }
                }.onSuccess {
                    respond { content = "Ticket accepted" }
                }
            }
        }
        ephemeralSlashCommand {
            name = Key("reject")
            description = Translations.reject

            guild(GUILD.id)
            requirePermission(Permission.ManageRoles)
            action {
                val ticket = STORAGE.tickets.values.find { it.channel == channel.id } ?: run {
                    respond { content = "Must be executed in a ticket channel" }
                    return@action
                }

                ChannelManager.reject(ticket).onFailure {
                    LOGGER.error("Failed to reject ticket", it)
                    respond { content = "Failed to reject ticket: ${it.message}" }
                }.onSuccess {
                    respond { content = "Ticket rejected" }
                }
            }
        }
        ephemeralSlashCommand {
            name = Key("reload")
            description = Translations.reload

            guild(GUILD.id)
            requirePermission(Permission.ManageRoles)
            action {
                CONFIG = load("config.json", BotConfig.Companion::empty)
                STORAGE = load("runtime.json", RuntimeData.Companion::empty)
                Main.update()
                respond { content = "Configuration reloaded" }
            }
        }
    }

    private inner class EditDiscordForm : ModalForm() {
        override var title: Key = Translations.edit
        val content = lineText {
            id = "content"
            label = Translations.edit
        }
    }
}