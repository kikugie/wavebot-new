package dev.kikugie.wavebot.sheet

import dev.kikugie.wavebot.Grid
import dev.kikugie.wavebot.Main.CONFIG
import dev.kikugie.wavebot.Main.JSON
import dev.kikugie.wavebot.Main.STORAGE
import dev.kordex.core.utils.env
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

    suspend fun request(type: String): Result<Table> {
        val source: String = CONFIG.sources[type]!!
        val sheet: String = CONFIG.sheets[type]!!
        val url: String = URL_BASE.format(source, sheet, TOKEN).replace(" ", "%20")
        val response: HttpResponse = CLIENT.get(url)
        return kotlin.runCatching {
            val unfiltered = JSON.decodeFromString<Table>(response.bodyAsText())
            Table(unfiltered.range, unfiltered.values.first(), unfiltered.values.filter { it.isApplication() })
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

    private fun List<String>.isApplication() = firstOrNull()?.startsWith("202") == true

    @Serializable
    data class Table(val range: String, val keys: List<String> = emptyList(), val values: Grid<String>)
}