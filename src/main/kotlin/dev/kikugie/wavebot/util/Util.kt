package dev.kikugie.wavebot.util

import dev.kikugie.wavebot.server.Dictionary
import kotlinx.serialization.Serializable

internal fun String.referring() = when (this.lastOrNull()) {
    's', 'x' -> "$this'"
    else -> "$this's"
}

@Serializable
class GroupingDictionary : Iterable<Dictionary> {
    val groups = mutableListOf<Dictionary>()
    inline fun group(block: MutableMap<String, String>.() -> Unit) {
        val map = mutableMapOf<String, String>().apply(block)
        if (map.isNotEmpty()) groups += map
    }

    override fun iterator(): Iterator<Dictionary> = groups.iterator()
}