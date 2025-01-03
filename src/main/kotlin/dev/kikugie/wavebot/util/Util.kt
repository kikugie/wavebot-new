package dev.kikugie.wavebot.util

import dev.kikugie.wavebot.server.Dictionary
import java.net.URI


internal fun String.referring() = when (this.lastOrNull()) {
    's', 'x' -> "$this'"
    else -> "$this's"
}

internal typealias GroupingDictionary = MutableList<Dictionary>
inline fun GroupingDictionary.group(block: MutableMap<String, String>.() -> Unit) {
    val map = mutableMapOf<String, String>().apply(block)
    if (map.isNotEmpty()) this += map
}

internal val URL_PATTERN = Regex("""[(htps)?:/w.a-zA-Z0-9@%_+~#=]{2,256}\.[a-z]{2,6}\b([-a-zA-Z0-9@:%_+.~#?&/=]*)""")

internal fun findLinks(text: String) = URL_PATTERN.findAll(text).map { it.value }.mapNotNull {
    val url = runCatching { URI.create(it).toURL() }.getOrNull() ?: return@mapNotNull null
    when (url.host) {
        "drive.google.com" -> it.substringAfter("?id=").let { id ->
            "https://drive.google.com/thumbnail?id=$id&sz=w1920"
        }
        else -> it
    }
}