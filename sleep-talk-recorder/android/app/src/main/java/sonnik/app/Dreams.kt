package sonnik.app

import android.content.Context
import org.json.JSONObject
import sonnik.core.Dream
import sonnik.core.DreamMood
import sonnik.core.NightFacts
import java.io.File
import java.time.LocalDateTime
import java.util.UUID

/** The dream journal: one JSON file per dream in files/dreams/. */
object DreamStore {
    private fun dir(ctx: Context) = File(ctx.filesDir, "dreams").apply { mkdirs() }

    fun new(now: LocalDateTime = LocalDateTime.now(), text: String = "") =
        Dream(UUID.randomUUID().toString(), now.withNano(0), text)

    fun save(ctx: Context, d: Dream) {
        val json = JSONObject()
            .put("id", d.id)
            .put("createdAt", d.createdAt.toString())
            .put("text", d.text)
            .put("mood", d.mood?.id ?: JSONObject.NULL)
            .put("notes", d.notes)
        val f = File(dir(ctx), "${d.id}.json")
        val tmp = File(f.parentFile, f.name + ".part")
        tmp.writeText(json.toString(2))
        tmp.renameTo(f)
    }

    private fun read(f: File): Dream? = runCatching {
        val j = JSONObject(f.readText())
        Dream(
            id = j.getString("id"),
            createdAt = LocalDateTime.parse(j.getString("createdAt")),
            text = j.optString("text"),
            mood = DreamMood.byId(if (j.isNull("mood")) null else j.optString("mood")),
            notes = j.optString("notes"),
        )
    }.getOrNull()

    fun list(ctx: Context): List<Dream> =
        dir(ctx).listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { read(it) }
            .sortedByDescending { it.createdAt }

    /** One dream, or null when it was never saved (it was still empty). */
    fun get(ctx: Context, id: String): Dream? =
        File(dir(ctx), "$id.json").takeIf { it.exists() }?.let { read(it) }

    fun delete(ctx: Context, d: Dream) = File(dir(ctx), "${d.id}.json").delete()

    /** What was recorded the night before this dream's morning, if anything; a nap that day comes second. */
    fun nightOf(ctx: Context, d: Dream): Night? {
        val sameDay = Nights.list(ctx).filter { it.morning == d.morning }
        return sameDay.firstOrNull { !it.daytime } ?: sameDay.firstOrNull()
    }

    fun factsOf(night: Night?): NightFacts? =
        night?.let { NightFacts(it.phrases, it.sounds, it.summary.snoreMinutes) }
}
