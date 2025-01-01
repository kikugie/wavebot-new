package dev.kikugie.wavebot.util

import dev.kikugie.wavebot.server.Dictionary
import kotlinx.serialization.Serializable

internal fun String.referring() = when (this.lastOrNull()) {
    's', 'x' -> "$this'"
    else -> "$this's"
}

@Serializable
class GroupingDictionary : MutableList<Dictionary> by mutableListOf() {
    inline fun group(block: MutableMap<String, String>.() -> Unit) {
        val map = mutableMapOf<String, String>().apply(block)
        if (map.isNotEmpty()) this += map
    }
}