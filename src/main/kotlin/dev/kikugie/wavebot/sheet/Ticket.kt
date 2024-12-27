package dev.kikugie.wavebot.sheet

import dev.kord.common.entity.Snowflake
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class Ticket(
    val member: Snowflake,
    val channel: Snowflake,
    var state: TicketState,
    var countdown: Instant = Instant.DISTANT_FUTURE
)

@Serializable
enum class TicketState {
    OPEN, ACCEPTED, REJECTED;
}