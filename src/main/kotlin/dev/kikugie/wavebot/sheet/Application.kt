package dev.kikugie.wavebot.sheet

import dev.kikugie.wavebot.Main.GUILD
import dev.kikugie.wavebot.util.GroupingDictionary
import dev.kikugie.wavebot.util.findLinks
import dev.kikugie.wavebot.util.group
import dev.kikugie.wavebot.util.referring
import dev.kord.common.Color
import dev.kord.core.behavior.channel.MessageChannelBehavior
import dev.kord.core.behavior.channel.createEmbed
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.entity.Member
import dev.kord.rest.builder.message.MessageBuilder
import dev.kord.rest.builder.message.embed
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.Serializable

private const val IDK = "?"
private inline fun ApplicationType.build(action: ApplicationData.() -> Unit) = kotlin.runCatching {
    ApplicationData(this).apply(action)
}

private fun List<String>.getOrIDK(index: Int) = getOrNull(index)?.takeIf(String::isNotBlank) ?: IDK
private fun MutableMap<String, String>.byIndex(index: Int, list1: List<String>, list2: List<String>) {
    put(list1[index], list2.getOrNull(index)?.takeIf { it.isNotBlank() } ?: return)
}

private fun ApplicationData.defaults(data: List<String>) {
    discord = data.getOrIDK(1)
    minecraft = data.getOrIDK(2)
    age = data.getOrIDK(3)
    timezone = data.getOrIDK(4)
    pronouns = data.getOrIDK(5)
}

private fun String.limitTo(n: Int) = if (length <= n) this else "${take(n - 3)}..."

@Serializable
data class ApplicationReference(
    val type: String,
    val index: Int
) {
    fun toEntry(map: Map<String, List<ApplicationData>>) = map[type]?.getOrNull(index)
}

@Serializable
class ApplicationData(val type: ApplicationType) {
    var discord: String = IDK
    var minecraft: String = IDK
    var pronouns: String = IDK
    var age: String = IDK
    var timezone: String = IDK

    val answers: GroupingDictionary = mutableListOf()

    fun toReference(list: List<ApplicationData>) = ApplicationReference(type.text, list.indexOf(this))

    suspend fun findMember(): Member? = Companion.findMember(discord)

    fun preview(builder: MessageBuilder) = builder.embed {
        color = type.color
        title = "${minecraft.referring()} ${type.text} application"
        field { name = "Discord"; value = discord; inline = true }
        field { name = "IGN"; value = minecraft; inline = true }
        field { name = "Age"; value = age; inline = true }
        field { name = "Pronouns"; value = pronouns; inline = true }
        field { name = "Timezone"; value = timezone; inline = true }
    }

    suspend fun contents(thread: MessageChannelBehavior) {
        val links: MutableList<String> = mutableListOf()
        for (it in answers) if (it.isNotEmpty()) thread.createEmbed {
            color = type.color
            for ((key, answer) in it) field {
                findLinks(answer).forEach(links::add)
                name = key
                value = answer.limitTo(1000)
            }
        }
        if (links.isNotEmpty()) for (it in links.windowed(5, 5, true)) thread.createMessage {
            content = it.joinToString("\n")
        }
    }

    companion object {
        @JvmStatic
        fun parse(type: String, keys: List<String>, data: List<String>): Result<ApplicationData> =
            ApplicationType.of(type).parse(keys, data.map { it.trim() })

        suspend fun findMember(discord: String): Member? = GUILD.members
            .filter { it.username == discord || it.tag == discord || it.effectiveName == discord }
            .firstOrNull()
    }
}

@Serializable
enum class ApplicationType {
    GENERAL {
        override val color: Color = Color(255, 0, 0)

        override fun parse(keys: List<String>, data: List<String>) = build {
            defaults(data)

            answers.group {// Test questions
                byIndex(6, keys, data)
                byIndex(7, keys, data)
                byIndex(8, keys, data)
            }

            answers.group {
                byIndex(9, keys, data)
                byIndex(10, keys, data)
                byIndex(11, keys, data)
                byIndex(12, keys, data)
                byIndex(13, keys, data)
            }

            answers.group {
                byIndex(14, keys, data)
            }
        }
    },
    CREATIVE {
        override val color: Color = Color(0, 0, 255)

        override fun parse(keys: List<String>, data: List<String>) = build {
            defaults(data)

            answers.group {
                byIndex(6, keys, data)
                byIndex(7, keys, data)
                byIndex(8, keys, data)
                byIndex(9, keys, data)
                byIndex(10, keys, data)
            }
            answers.group {
                byIndex(11, keys, data)
            }
        }
    },
    BUILDER {
        override val color: Color = Color(0, 255, 0)

        override fun parse(keys: List<String>, data: List<String>) = build {
            defaults(data)

            answers.group {
                byIndex(6, keys, data)
                byIndex(7, keys, data)
                byIndex(8, keys, data)
                byIndex(9, keys, data)
            }
            answers.group {
                byIndex(10, keys, data)
            }
        }
    };

    val text get() = toString().lowercase()
    val id get() = text.first()
    abstract val color: Color
    abstract fun parse(keys: List<String>, data: List<String>): Result<ApplicationData>

    companion object {
        @JvmStatic
        fun of(value: String) = valueOf(value.uppercase())
    }
}
