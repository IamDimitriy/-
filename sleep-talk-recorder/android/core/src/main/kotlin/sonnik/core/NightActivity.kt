package sonnik.core

import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.max

/** One minute of the night, as heard by the microphone. */
data class Minute(
    /** Minutes since the night started. */
    val index: Int,
    val speechS: Double,
    val snoreS: Double,
    /** Other noticeable sounds: turning over, rustling, coughing. */
    val noiseS: Double,
    /** Share of the minute that was audibly above the room's silence (0..1): restlessness. */
    val activity: Double,
) {
    fun toCsv() = "%d,%.1f,%.1f,%.1f,%.3f".format(java.util.Locale.ROOT, index, speechS, snoreS, noiseS, activity)

    companion object {
        const val CSV_HEADER = "minute,speech_s,snore_s,noise_s,activity"

        fun fromCsv(line: String): Minute? {
            val p = line.split(',')
            if (p.size < 5) return null
            return runCatching {
                Minute(p[0].toInt(), p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toDouble())
            }.getOrNull()
        }
    }
}

/**
 * Turns the detector's frames into per-minute statistics.
 *
 * Snoring is told apart from other low sounds by its rhythm: a minute counts as snoring only if
 * it holds at least [minSnoreBursts] separate low-pitched bursts (one per breath), so a door
 * slam or a truck outside does not.
 */
class ActivityMeter(
    private val thresholdDb: Double = 10.0,
    /** Frames this much above the silence count as restlessness. */
    private val activityDb: Double = 6.0,
    private val minSnoreBurstS: Double = 0.3,
    private val minSnoreBursts: Int = 3,
    private val lowRatioForSnore: Double = 0.5,
    /** Stream time (s) that maps to minute 0; earlier frames are ignored. */
    private val startS: Double = 0.0,
) {
    private var minute = -1
    private var frames = 0
    private var active = 0
    private var speechS = 0.0
    private var noiseS = 0.0
    private var snoreCandidateS = 0.0
    private var bursts = 0
    private var runS = 0.0

    /** Returns the minute that just ended, if this frame starts a new one. */
    fun add(f: FrameInfo): Minute? {
        if (f.timeS < startS) return null
        val m = ((f.timeS - startS) / 60).toInt()
        var closed: Minute? = null
        if (m != minute) {
            if (minute >= 0) closed = close()
            minute = m
        }
        frames++
        val over = f.db - f.floorDb
        val loud = over >= thresholdDb
        val snoreLike = loud && !f.speech && f.lowRatio >= lowRatioForSnore
        if (over >= activityDb) active++
        when {
            f.speech -> speechS += f.durationS
            snoreLike -> {}
            over >= activityDb -> noiseS += f.durationS
        }
        if (snoreLike) {
            runS += f.durationS
        } else {
            endRun()
        }
        return closed
    }

    /** Closes the minute in progress (end of the night). */
    fun flush(): Minute? = if (minute >= 0 && frames > 0) close().also { minute = -1 } else null

    private fun endRun() {
        if (runS >= minSnoreBurstS) {
            bursts++
            snoreCandidateS += runS
        } else {
            noiseS += runS // a short low thump is just noise
        }
        runS = 0.0
    }

    private fun close(): Minute {
        endRun()
        val snore = if (bursts >= minSnoreBursts) snoreCandidateS else 0.0
        val noise = noiseS + (snoreCandidateS - snore)
        val out = Minute(minute, speechS, snore, noise, if (frames > 0) active.toDouble() / frames else 0.0)
        frames = 0; active = 0; speechS = 0.0; noiseS = 0.0; snoreCandidateS = 0.0; bursts = 0
        return out
    }
}

/** Night totals for the summary line and the morning notification. */
data class NightSummary(val minutes: Int, val snoreMinutes: Int, val speechS: Double, val restless: Double) {
    companion object {
        fun of(minutes: List<Minute>): NightSummary = NightSummary(
            minutes = minutes.size,
            // A minute with 10+ s of snoring counts as a snoring minute.
            snoreMinutes = minutes.count { it.snoreS >= 10.0 },
            speechS = minutes.sumOf { it.speechS },
            restless = if (minutes.isEmpty()) 0.0 else minutes.map { it.activity }.average(),
        )
    }
}

/**
 * Smart alarm: wake during light sleep inside the window before the alarm time.
 *
 * Sound only gives a rough idea of sleep depth: deep sleep is quiet and still, light sleep is
 * restless (turning over, talking). So the alarm rings early when the last few minutes are
 * clearly more restless than the night's usual level, and at the alarm time at the latest.
 */
object SmartWake {
    const val RECENT_MINUTES = 3

    fun shouldWake(now: LocalDateTime, alarmAt: LocalDateTime, windowMin: Int, night: List<Minute>): Boolean {
        if (!now.isBefore(alarmAt)) return true
        if (now.isBefore(alarmAt.minusMinutes(windowMin.toLong()))) return false
        if (night.size < RECENT_MINUTES) return false
        val recent = night.takeLast(RECENT_MINUTES)
        if (recent.any { it.speechS >= 1.0 }) return true // talking: not deep asleep
        val level = recent.map { it.activity }.average()
        val usual = night.map { it.activity }.sorted()[night.size / 2]
        return level >= max(MIN_RESTLESS, usual * 2.5)
    }

    /** The first time [alarm] o'clock happens after [from]. */
    fun nextAlarm(from: LocalDateTime, alarm: LocalTime): LocalDateTime {
        val sameDay = from.toLocalDate().atTime(alarm)
        return if (sameDay.isAfter(from)) sameDay else sameDay.plusDays(1)
    }

    private const val MIN_RESTLESS = 0.08
}
