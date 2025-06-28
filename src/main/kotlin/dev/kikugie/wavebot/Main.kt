@file:OptIn(ExperimentalSerializationApi::class)

package dev.kikugie.wavebot

import dev.kikugie.wavebot.Main.JSON
import dev.kikugie.wavebot.bot.WavebotExtension
import dev.kikugie.wavebot.server.BotConfig
import dev.kikugie.wavebot.server.ChannelManager
import dev.kikugie.wavebot.server.RuntimeData
import dev.kikugie.wavebot.server.Saveable
import dev.kikugie.wavebot.sheet.Spreadsheet
import dev.kikugie.wavebot.sheet.TicketState
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.entity.Guild
import dev.kordex.core.ExtensibleBot
import dev.kordex.core.utils.env
import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import org.slf4j.LoggerFactory
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.inputStream

typealias Grid<T> = List<List<T>>

suspend fun main(args: Array<String>) = Main.main()
@OptIn(ExperimentalSerializationApi::class)
internal inline fun <reified T : Saveable> load(path: String, default: () -> T) = Path(path).let {
    if (it.exists()) it.inputStream().use { stream -> JSON.decodeFromStream<T>(stream) }
    else default().apply { save() }
}

object Main {
    val JSON = Json { ignoreUnknownKeys = true; prettyPrint = true; prettyPrintIndent = "  " }
    val TEST_SERVER_ID = Snowflake(env("TEST_SERVER").toLong())
    internal val LOGGER = LoggerFactory.getLogger("Wavebot")
    internal val TOKEN = env("DISCORD_TOKEN")
    internal var CONFIG: BotConfig = load("config.json", BotConfig.Companion::empty)
    internal var STORAGE: RuntimeData = load("runtime.json", RuntimeData.Companion::empty)
    internal lateinit var GUILD: Guild
        private set
    internal lateinit var KORD: Kord
        private set

    @JvmStatic
    suspend fun main() {
        KORD = Kord(TOKEN)
        GUILD = KORD.getGuild(TEST_SERVER_ID)

        val bot = ExtensibleBot(TOKEN) {
            extensions {
                add(::WavebotExtension)
            }
        }
        CoroutineScope(Dispatchers.Default).launch { ping() }
        bot.start()
    }

    private suspend fun CoroutineScope.ping() {
        var n = 0
        while (isActive) {
            LOGGER.info("Pinging server every ${CONFIG.refresh}ms (${++n} attempt)")
            update()
            delay(CONFIG.refresh)
        }
    }

    internal suspend fun update() {
        for (it in CONFIG.sheets.keys) Spreadsheet.request(it, CONFIG).onSuccess { sheet ->
            val new = Spreadsheet.update(it, sheet)
            LOGGER.info("Received ${new.size} new applications for '$it'")
            for (data in new) ChannelManager.preview(data).onFailure { e ->
                LOGGER.error("Failed to create post for ${data.minecraft} ($it)", e)
            }
        }.onFailure { e ->
            LOGGER.error("Failed to fetch spreadsheet for '$it'", e)
        }
        val now = Clock.System.now()
        for ((_, it) in STORAGE.tickets)
            if (it.state == TicketState.REJECTED && now - it.countdown <= CONFIG.countdown)
                ChannelManager.delete(it)
    }
}