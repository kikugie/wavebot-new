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

internal val URL_PATTERN = Regex("""(https://www\.|http://www\.|https://|http://)?[a-zA-Z]{2,}(\.[a-zA-Z]{2,})(\.[a-zA-Z]{2,})?/[a-zA-Z0-9]{2,}|((https://www\.|http://www\.|https://|http://)?[a-zA-Z]{2,}(\.[a-zA-Z]{2,})(\.[a-zA-Z]{2,})?)|(https://www\.|http://www\.|https://|http://)?[a-zA-Z0-9]{2,}\.[a-zA-Z0-9]{2,}\.[a-zA-Z0-9]{2,}(\.[a-zA-Z0-9]{2,})?""")

internal fun findLinks(text: String) = URL_PATTERN.findAll(text).map { it.value }.mapNotNull {
    val url = runCatching { URI.create(it).toURL() }.getOrNull() ?: return@mapNotNull null
    when (url.host) {
        "drive.google.com" -> it.substringAfter("?id=").substringBefore("&").let { id ->
            "https://drive.google.com/thumbnail?id=$id&sz=w1920"
        }
        else -> it
    }
}