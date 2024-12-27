package dev.kikugie.wavebot.server

import dev.kord.common.entity.Snowflake
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.time.Duration

typealias Dictionary = Map<String, String>

@Serializable
data class BotConfig(
    val sources: Dictionary,
    val sheets: Dictionary,
    val refresh: Duration,
    val countdown: Duration,
    val server: ServerData
) : Saveable {
    @Transient
    override val file: String = "config.json"

    companion object {
        fun empty() = BotConfig(emptyMap(), emptyMap(), Duration.ZERO, Duration.ZERO, ServerData())
    }
}

@Serializable
data class ServerData(
    @SerialName("preview_channel_id") val previewChannel: Snowflake = Snowflake(0),
    @SerialName("application_category_id") val applicationCategory: Snowflake = Snowflake(0),
    @SerialName("archive_category_id") val archiveCategory: Snowflake = Snowflake(0),
    @SerialName("applicant_role_id") val applicantRole: Snowflake = Snowflake(0),
    @SerialName("provisional_role_id") val provisionalRole: Snowflake = Snowflake(0),
    @SerialName("voice_channel_id") val voiceChannel: Snowflake = Snowflake(0),
)