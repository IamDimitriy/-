package sonnik.app

import android.content.Context
import sonnik.core.Episode
import sonnik.core.Minute
import sonnik.core.NightSummary
import sonnik.core.SoundClass
import sonnik.core.SoundKind
import sonnik.core.Wav
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class Clip(
    val file: File,
    val at: LocalDateTime,
    val durationS: Double,
    val sound: SoundClass = SoundClass(SoundKind.SPEECH),
)

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

    val phrases: Int get() = clips.count { it.sound.kind == SoundKind.SPEECH }

    /** Saved clips that are not speech. */
    val sounds: Int get() = clips.size - phrases
}

/**
 * Recordings live in files/nights/<night start>/<clip time>__<kind>[-<detail>].wav, e.g.
 * 2026-10-09_03-12-45__move-door.wav. Clips from versions before categories have no suffix
 * and are speech.
 */
object Nights {
    private val dirFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm")
    private val clipFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

    fun root(ctx: Context) = File(ctx.filesDir, "nights").apply { mkdirs() }

    fun dirFor(ctx: Context, start: LocalDateTime) = File(root(ctx), start.format(dirFmt)).apply { mkdirs() }

    private val clipName = Regex("""^(\d{4}-\d\d-\d\d_\d\d-\d\d-\d\d)(?:__([a-z]+)(?:-([a-z]+))?)?(?:[_~]\d+)?\.wav$""")

    fun save(nightDir: File, at: LocalDateTime, ep: Episode, sound: SoundClass = SoundClass(SoundKind.SPEECH)): File {
        val base = at.format(clipFmt) + "__" + sound.kind.id + (sound.detail?.let { "-$it" } ?: "")
        var f = File(nightDir, "$base.wav")
        var n = 1
        while (f.exists()) f = File(nightDir, "$base~${n++}.wav")
        // Quiet mumbling is raised so it can be heard on playback.
        Wav.write(f, Wav.normalized(ep.audio), ep.sampleRate)
        return f
    }

    internal fun parseClip(f: File): Clip? {
        val m = clipName.matchEntire(f.name) ?: return null
        val at = runCatching { LocalDateTime.parse(m.groupValues[1], clipFmt) }.getOrNull() ?: return null
        val kind = SoundKind.byId(m.groupValues[2].ifEmpty { null }) ?: SoundKind.SPEECH
        return Clip(f, at, Wav.durationS(f), SoundClass(kind, m.groupValues[3].ifEmpty { null }))
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
            val clips = dir.listFiles { f -> f.name.endsWith(".wav") }.orEmpty()
                .mapNotNull(::parseClip)
                .sortedBy { it.at }
            Night(dir, start, clips, minutes(dir))
        }.sortedByDescending { it.start }

    fun delete(clip: Clip) = clip.file.delete()

    fun delete(night: Night) = night.dir.deleteRecursively()
}
