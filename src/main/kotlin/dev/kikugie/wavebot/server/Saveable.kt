package dev.kikugie.wavebot.server

import dev.kikugie.wavebot.Main
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Polymorphic
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.encodeToStream
import java.nio.file.StandardOpenOption
import kotlin.io.path.Path
import kotlin.io.path.outputStream

@OptIn(ExperimentalSerializationApi::class) @Polymorphic @Serializable
sealed interface Saveable {
    @Transient val file: String
    fun save() = Path(file).outputStream(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use {
        Main.JSON.encodeToStream(this, it)
    }
}