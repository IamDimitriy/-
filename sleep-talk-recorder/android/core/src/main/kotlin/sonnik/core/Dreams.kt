package sonnik.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class DreamMood(val id: String, val title: String) {
    GOOD("good", "Приятный"),
    NEUTRAL("neutral", "Обычный"),
    ANXIOUS("anxious", "Тревожный"),
    NIGHTMARE("nightmare", "Кошмар");

    companion object {
        fun byId(id: String?): DreamMood? = entries.firstOrNull { it.id == id }
    }
}

/** A dream written down after waking up. [notes] holds the user's thoughts or a pasted interpretation. */
data class Dream(
    val id: String,
    val createdAt: LocalDateTime,
    val text: String,
    val mood: DreamMood? = null,
    val notes: String = "",
) {
    /** The morning the dream belongs to, which is also the night it was dreamt. */
    val morning: LocalDate get() = createdAt.toLocalDate()

    /** First sentence or line, for lists. */
    val title: String
        get() {
            val first = text.trim().split('\n', '.', '!', '?').firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            return when {
                first.isEmpty() -> "Без текста"
                first.length <= 60 -> first
                else -> first.take(59).trimEnd() + "…"
            }
        }
}

/** What the app heard that night, to give the interpretation some context. */
data class NightFacts(val phrases: Int, val sounds: Int, val snoreMinutes: Int)

object DreamSearch {
    private fun norm(s: String) = s.lowercase(Locale.ROOT).replace('ё', 'е')

    /** Every word of [query] must appear in the dream or its notes, ignoring case and ё/е. */
    fun matches(d: Dream, query: String): Boolean {
        val words = norm(query).split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return true
        val hay = norm(d.text + " " + d.notes)
        return words.all { it in hay }
    }
}

/**
 * The request sent to an assistant app (Claude, ChatGPT) through Android's share sheet.
 * It asks for a reflective reading rather than fortune-telling.
 */
object DreamPrompt {
    private val day = DateTimeFormatter.ofPattern("d MMMM", Locale("ru"))

    fun build(d: Dream, night: NightFacts? = null): String = buildString {
        append("Мне приснился сон. Помоги понять, о чём он может говорить: какие в нём эмоции и образы, ")
        append("с чем из моей жизни они могут перекликаться и какие вопросы стоит себе задать. ")
        append("Не предсказывай будущее и не ставь диагнозов. Ответь по-русски, коротко и по делу.\n\n")
        append("Сон (записан утром ${d.morning.format(day)}):\n")
        append(d.text.trim())
        append('\n')
        d.mood?.let { append("\nНастроение сна: ${it.title.lowercase(Locale("ru"))}.\n") }
        if (night != null && (night.phrases > 0 || night.snoreMinutes > 0 || night.sounds > 0)) {
            val facts = buildList {
                if (night.phrases > 0) add("фраз во сне: ${night.phrases}")
                if (night.snoreMinutes > 0) add("храп около ${night.snoreMinutes} мин")
                if (night.sounds > 0) add("${night.sounds} других звуков в комнате")
            }
            append("\nТой ночью приложение записало: ${facts.joinToString(", ")}.\n")
        }
    }
}
