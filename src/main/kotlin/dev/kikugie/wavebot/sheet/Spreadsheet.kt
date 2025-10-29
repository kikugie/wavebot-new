package dev.kikugie.wavebot.sheet

import dev.kikugie.wavebot.Grid
import dev.kikugie.wavebot.Main.CONFIG
import dev.kikugie.wavebot.Main.JSON
import dev.kikugie.wavebot.Main.STORAGE
import dev.kikugie.wavebot.server.BotConfig
import dev.kordex.core.utils.env
import dev.kordex.core.utils.getOfOrDefault
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@OptIn(ExperimentalSerializationApi::class)
object Spreadsheet {
    private const val URL_BASE = "https://sheets.googleapis.com/v4/spreadsheets/%s/values/%s?alt=json&key=%s"
    private val TOKEN = env("GOOGLE_TOKEN")
    private val CLIENT = HttpClient()

    suspend fun request(type: String, config: BotConfig): Result<Table> {
        val source: String = CONFIG.source
        val sheet: String = CONFIG.sheets[type]!!
        val url: String = URL_BASE.format(source, sheet, TOKEN).replace(" ", "%20")
        val response: HttpResponse = CLIENT.get(url)
        return kotlin.runCatching {
            val unfiltered = JSON.decodeFromString<Table>(response.bodyAsText())
            val known = unfiltered.values.drop(config.offsets.getOfOrDefault(type, 0) + 1)
            Table(unfiltered.range, unfiltered.values.first(), known)
        }
    }

    fun update(type: String, data: Table): List<ApplicationData> {
        val container = STORAGE.applications.getOrPut(type) { mutableListOf() }
        if (container.size >= data.values.size) return emptyList()
        val new: List<ApplicationData> = data.values.subList(container.size, data.values.size).map {
            ApplicationData.parse(type, data.keys, it).getOrThrow()
        }
        container += new
        STORAGE.save()
        return new
    }

    @Serializable
    data class Table(val range: String, val keys: List<String> = emptyList(), val values: Grid<String>)
}