package dev.kikugie.wavebot.server

import dev.kikugie.wavebot.sheet.ApplicationData
import dev.kikugie.wavebot.sheet.ApplicationReference
import dev.kikugie.wavebot.sheet.Ticket
import dev.kord.common.entity.Snowflake
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@ExperimentalSerializationApi
@Serializable
data class RuntimeData(
    val messages: MutableMap<Snowflake, ApplicationReference> = mutableMapOf(),
    val tickets: MutableMap<Snowflake, Ticket> = mutableMapOf(),
    val applications: MutableMap<String, MutableList<ApplicationData>> = mutableMapOf(),
): Saveable {
    @Transient
    override val file: String = "runtime.json"

    override fun save() = synchronized(RuntimeData) { super.save()}
    companion object {
        fun empty() = RuntimeData()
    }
}