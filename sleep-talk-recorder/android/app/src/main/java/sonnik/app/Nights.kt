package sonnik.app

import android.content.Context
import sonnik.core.Episode
import sonnik.core.Minute
import sonnik.core.NightSummary
import sonnik.core.Wav
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class Clip(val file: File, val at: LocalDateTime, val durationS: Double)

data class Night(
    val dir: File,
    val start: LocalDateTime,
    val clips: List<Clip>,
    /** Minute-by-minute sound statistics; empty for nights recorded before they existed. */
    val minutes: List<Minute> = emptyList(),
) {
    /** The morning this night belongs to: a night started at 23:30 on the 8th is "the night to the 9th". */
    val morning get() = if (start.hour >= 12) start.toLocalDate().plusDays(1) else start.toLocalDate()

    val summary: NightSummary get() = NightSummary.of(minutes)
}

/** Recordings live in files/nights/<night start>/<clip time>.wav. */
object Nights {
    private val dirFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm")
    private val clipFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

    fun root(ctx: Context) = File(ctx.filesDir, "nights").apply { mkdirs() }

    fun dirFor(ctx: Context, start: LocalDateTime) = File(root(ctx), start.format(dirFmt)).apply { mkdirs() }

    fun save(nightDir: File, at: LocalDateTime, ep: Episode): File {
        var f = File(nightDir, at.format(clipFmt) + ".wav")
        var n = 1
        while (f.exists()) f = File(nightDir, at.format(clipFmt) + "_${n++}.wav")
        Wav.write(f, ep.audio, ep.sampleRate)
        return f
    }

    private const val ACTIVITY = "activity.csv"

    /** Appends one minute of statistics to the night's activity.csv. */
    fun appendMinute(nightDir: File, m: Minute) {
        val f = File(nightDir, ACTIVITY)
        if (!f.exists()) f.writeText(Minute.CSV_HEADER + "\n")
        f.appendText(m.toCsv() + "\n")
    }

    fun minutes(nightDir: File): List<Minute> {
        val f = File(nightDir, ACTIVITY)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull(Minute::fromCsv)
    }

    fun list(ctx: Context): List<Night> =
        root(ctx).listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { dir ->
            val start = runCatching { LocalDateTime.parse(dir.name, dirFmt) }.getOrNull() ?: return@mapNotNull null
            val clips = dir.listFiles { f -> f.name.endsWith(".wav") }.orEmpty().mapNotNull { f ->
                val at = runCatching { LocalDateTime.parse(f.name.take(19), clipFmt) }.getOrNull()
                    ?: return@mapNotNull null
                Clip(f, at, Wav.durationS(f))
            }.sortedBy { it.at }
            Night(dir, start, clips, minutes(dir))
        }.sortedByDescending { it.start }

    fun delete(clip: Clip) = clip.file.delete()

    fun delete(night: Night) = night.dir.deleteRecursively()
}
