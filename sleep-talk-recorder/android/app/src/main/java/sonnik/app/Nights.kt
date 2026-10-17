package sonnik.app

import android.content.Context
import sonnik.core.Episode
import sonnik.core.Heard
import sonnik.core.Minute
import sonnik.core.NightSummary
import sonnik.core.SoundClass
import sonnik.core.SoundKind
import sonnik.core.Wav
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val titleDay = DateTimeFormatter.ofPattern("d MMMM", Locale("ru"))

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
    /** When the sleepers fell asleep (speech before it was not kept), if it was noted. */
    val asleepAt: LocalDateTime? = null,
) {
    /**
     * The date this sleep belongs to: a night started at 23:30 on the 8th is "the night to the 9th",
     * one started at 01:00 on the 9th too; a nap at 16:00 on the 8th belongs to the 8th.
     */
    val morning get() = if (start.hour >= 18) start.toLocalDate().plusDays(1) else start.toLocalDate()

    /** Started between 06:00 and 18:00: a nap or a test rather than a night. */
    val daytime: Boolean get() = start.hour in 6..17

    /** "Ночь на 9 октября", or "Днём, 8 октября" for a daytime session. */
    val title: String get() = (if (daytime) "Днём, " else "Ночь на ") + morning.format(titleDay)

    val summary: NightSummary get() = NightSummary.of(minutes)

    /** Sure phrases. */
    val phrases: Int get() = clips.count { it.sound.kind == SoundKind.SPEECH }

    /** Bits that may be a voice, kept apart from the phrases. */
    val unsure: Int get() = clips.count { it.sound.kind == SoundKind.UNSURE }

    /** Samples of other sounds: snoring, knocks, coughs. */
    val sounds: Int get() = clips.size - phrases - unsure
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
    private const val NOTES = "clips.csv"
    private const val ASLEEP = "asleep.txt"
    private val noteFmt = DateTimeFormatter.ofPattern("HH:mm:ss")

    /**
     * Writes down what was decided about a sound and what the classifier heard ("phrase file",
     * "шум", "до сна"...), so a night's report can show why something was or was not kept.
     */
    fun note(nightDir: File, at: LocalDateTime, outcome: String, heard: Heard) {
        val f = File(nightDir, NOTES)
        if (!f.exists()) f.writeText("time,outcome,speech,heard\n")
        val speech = String.format(java.util.Locale.ROOT, "%.2f", heard.speech)
        f.appendText("${at.format(noteFmt)},$outcome,$speech,\"${heard.top().replace('"', '\'')}\"\n")
    }

    fun setAsleep(nightDir: File, at: LocalDateTime) = File(nightDir, ASLEEP).writeText(at.withNano(0).toString())

    private fun asleepAt(nightDir: File): LocalDateTime? =
        runCatching { LocalDateTime.parse(File(nightDir, ASLEEP).readText().trim()) }.getOrNull()

    /**
     * The night in text: what was kept, when they fell asleep, and the decisions with what the
     * classifier heard. Noise is most of a night, so of it only the count and the bits that came
     * closest to a voice: those tell whether the thresholds suit this room.
     */
    fun report(night: Night): String = buildString {
        appendLine("Сонник, ${night.title}")
        appendLine("Начало записи ${night.start.toLocalTime().withSecond(0)}" +
            (night.asleepAt?.let { ", уснули ≈ ${it.toLocalTime().withSecond(0).withNano(0)}" } ?: ""))
        appendLine("Фраз: ${night.phrases}, неразборчиво: ${night.unsure}, звуков: ${night.sounds}, храп ${night.summary.snoreMinutes} мин")
        val notes = File(night.dir, NOTES)
        if (!notes.exists()) return@buildString
        val (noise, rest) = notes.readLines().drop(1).partition { it.split(',').getOrNull(1) == NOISE }
        appendLine("Отброшено как шум: ${noise.size}")
        appendLine()
        appendLine("время,решение,голос,что услышано")
        rest.takeLast(REPORT_LINES).forEach { appendLine(it) }
        if (noise.isNotEmpty()) {
            appendLine()
            appendLine("Шум, больше всего похожий на голос:")
            noise.sortedByDescending { it.split(',').getOrNull(2)?.toFloatOrNull() ?: 0f }
                .take(REPORT_NOISE).sorted().forEach { appendLine(it) }
        }
    }

    /** The outcome noted for silence and steady noise. */
    const val NOISE = "шум"
    private const val REPORT_LINES = 600
    private const val REPORT_NOISE = 60

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
            Night(dir, start, clips, minutes(dir), asleepAt(dir))
        }.sortedByDescending { it.start }

    /**
     * The nights worth a card: a session that saved nothing and stopped within minutes (a test
     * start, say) is left out, though its files stay. The newest night is kept while it records.
     */
    fun shown(nights: List<Night>, recording: Boolean): List<Night> =
        nights.filterIndexed { i, n -> n.clips.isNotEmpty() || n.minutes.size >= 5 || (recording && i == 0) }

    fun delete(clip: Clip) = clip.file.delete()

    fun delete(night: Night) = night.dir.deleteRecursively()
}
