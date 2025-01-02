package dev.kikugie.wavebot.bot

import dev.kikugie.wavebot.Main
import dev.kikugie.wavebot.Main.CONFIG
import dev.kikugie.wavebot.Main.GUILD
import dev.kikugie.wavebot.Main.LOGGER
import dev.kikugie.wavebot.Main.STORAGE
import dev.kikugie.wavebot.load
import dev.kikugie.wavebot.server.BotConfig
import dev.kikugie.wavebot.i18n.Translations.Wavebot.Extension as Translations
import dev.kikugie.wavebot.server.ChannelManager
import dev.kikugie.wavebot.server.RuntimeData
import dev.kord.common.entity.Permission
import dev.kord.common.entity.TextInputStyle
import dev.kord.core.behavior.interaction.modal
import dev.kord.core.event.interaction.ButtonInteractionCreateEvent
import dev.kord.core.event.interaction.ModalSubmitInteractionCreateEvent
import dev.kordex.core.components.forms.ModalForm
import dev.kordex.core.extensions.Extension
import dev.kordex.core.extensions.ephemeralMessageCommand
import dev.kordex.core.extensions.ephemeralSlashCommand
import dev.kordex.core.extensions.event
import dev.kordex.core.i18n.types.Key
import kotlinx.serialization.ExperimentalSerializationApi

@OptIn(ExperimentalSerializationApi::class)
class WavebotExtension : Extension() {
    override val name: String = "wavebot"

    override suspend fun setup() {
        event<ButtonInteractionCreateEvent> {
            check {
                failIf(!event.interaction.componentId.startsWith("wavebot/ticket"))
            }

            action {
                val message = event.interaction.message
                LOGGER.debug("Processing ticket button interaction: {}", message.id)

                val entry = STORAGE.messages[message.id]?.toEntry(STORAGE.applications) ?: run {
                    LOGGER.error("Selected message is not an application")
                    return@action
                }

                when (event.interaction.componentId.substringAfterLast('/')) {
                    "open" -> ChannelManager.ticket(message, entry).onFailure {
                        LOGGER.error("Failed to create ticket", it)
                    }

                    "deny" -> ChannelManager.deny(message, entry).onFailure {
                        LOGGER.error("Failed to deny ticket", it)
                    }

                    "edit" -> {
                        event.interaction.modal("Edit discord", "wavebot/form/edit") {
                            actionRow {
                                textInput(TextInputStyle.Short, "content", "Edit discord") {
                                    required = true
                                }
                            }
                        }
                    }

                    else -> LOGGER.error("Unknown ticket button interaction: {}", event.interaction.componentId)
                }
            }
        }

        event<ModalSubmitInteractionCreateEvent> {
            check {
                failIf(!event.interaction.modalId.startsWith("wavebot/form/edit"))
            }
            action {
                val entry = STORAGE.messages[event.interaction.message?.id]?.toEntry(STORAGE.applications) ?: run {
                    LOGGER.error("Selected message is not an application")
                    return@action
                }

                val content = event.interaction.textInputs["content"]?.value ?: run {
                    LOGGER.error("No content provided")
                }

                ChannelManager.edit(event.interaction.message!!, entry, content.toString())
            }
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
            name = Translations.deny

            guild(GUILD.id)
            requirePermission(Permission.ManageRoles)
            action {
                targetMessages.forEach { message ->
                    val entry = STORAGE.messages[message.id]?.toEntry(STORAGE.applications) ?: run {
                        respond { content = "Selected message is not an application" }
                        return@forEach
                    }
                    val content = it?.content?.value ?: run {
                        respond { content = "No content provided" }
                        return@action
                    }
                    ChannelManager.edit(message, entry, content)
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
            label = Translations.edit
        }
    }
}