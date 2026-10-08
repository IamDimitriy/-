package sonnik.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Streaming speech-episode detector; the same algorithm as sleeptalk/detector.py and web/detector.js.
 *
 * A frame counts as speech when it is [thresholdDb] above the adaptive noise floor and most of
 * its energy lies in the 300–3400 Hz band, which rejects snoring, hum and traffic rumble.
 */
data class DetectorConfig(
    val frameMs: Double = 30.0,
    val thresholdDb: Double = 10.0,
    val bandLowHz: Double = 300.0,
    val bandHighHz: Double = 3400.0,
    val minSpeechRatio: Double = 0.45,
    val startActive: Int = 5,
    val startWindow: Int = 10,
    val prerollS: Double = 2.0,
    val hangoverS: Double = 2.5,
    // Sleep talk is often one short word, so 0.25 s of speech is enough to keep a clip.
    val minActiveS: Double = 0.25,
    val maxEventS: Double = 120.0,
    /**
     * The room's silence is the [floorPercentile] of frame levels over the last [floorWindowS].
     * A percentile ignores short loud sounds (a snore must not mask the words right after it)
     * and still follows a lasting change such as a fan switched on.
     */
    val floorWindowS: Double = 30.0,
    val floorPercentile: Double = 0.2,
    val calibrationS: Double = 3.0,
    val minFloorDb: Double = -70.0,
)

class Episode(
    /** Offset of the first sample from the start of the stream, seconds. */
    val startS: Double,
    val sampleRate: Int,
    val audio: FloatArray,
    val peakDb: Double,
    val activeS: Double,
) {
    val durationS: Double get() = audio.size.toDouble() / sampleRate
}

private class Biquad(lowpass: Boolean, f0: Double, sr: Int) {
    private val b0: Double; private val b1: Double; private val b2: Double
    private val a1: Double; private val a2: Double
    private var z1 = 0.0; private var z2 = 0.0

    init {
        val w = 2 * PI * min(f0, sr * 0.45) / sr
        val c = cos(w)
        val alpha = sin(w) / (2 * sqrt(0.5))
        val a0 = 1 + alpha
        val nb0: Double; val nb1: Double
        if (lowpass) { nb0 = (1 - c) / 2; nb1 = 1 - c } else { nb0 = (1 + c) / 2; nb1 = -(1 + c) }
        b0 = nb0 / a0; b1 = nb1 / a0; b2 = nb0 / a0
        a1 = -2 * c / a0; a2 = (1 - alpha) / a0
    }

    fun next(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }
}

class Detector(val sampleRate: Int, val cfg: DetectorConfig = DetectorConfig()) {
    val frameLen = max(1, (sampleRate * cfg.frameMs / 1000).roundToInt())
    private val fps = sampleRate.toDouble() / frameLen
    private val prerollMax = max(1, (cfg.prerollS * fps).roundToInt())
    private val hangFrames = max(1, (cfg.hangoverS * fps).roundToInt())
    private val minActive = max(1, (cfg.minActiveS * fps).roundToInt())
    private val maxFrames = max(1, (cfg.maxEventS * fps).roundToInt())
    private val history = DoubleArray(max(1, (cfg.floorWindowS * fps).roundToInt()))
    private var historyCount = 0
    private var historyPos = 0
    private val calibFrames = max(1, (cfg.calibrationS * fps).roundToInt())

    private val hp60 = Biquad(false, 60.0, sampleRate)
    private val bandHp1 = Biquad(false, cfg.bandLowHz, sampleRate)
    private val bandHp2 = Biquad(false, cfg.bandLowHz, sampleRate)
    private val bandLp1 = Biquad(true, cfg.bandHighHz, sampleRate)
    private val bandLp2 = Biquad(true, cfg.bandHighHz, sampleRate)

    private val calib = ArrayList<Double>()
    /** Noise floor in dBFS, or null while calibrating. */
    var floorDb: Double? = null
        private set
    /** Level of the most recent frame, dBFS. */
    var lastDb: Double = -120.0
        private set

    private val preroll = ArrayDeque<FloatArray>()
    private val recent = ArrayDeque<Boolean>()
    private var frameIdx = 0L
    private var buf = FloatArray(frameLen)
    private var fill = 0

    private var cur: Current? = null
    val inEpisode: Boolean get() = cur != null

    private class Current(val startFrame: Long, val frames: MutableList<FloatArray>, var active: Int, var peakDb: Double) {
        var silentRun = 0
    }

    fun process(chunk: FloatArray, length: Int = chunk.size): List<Episode> {
        val out = ArrayList<Episode>(0)
        for (i in 0 until length) {
            buf[fill++] = chunk[i]
            if (fill == frameLen) {
                step(buf)?.let(out::add)
                buf = FloatArray(frameLen)
                fill = 0
            }
        }
        return out
    }

    /** 16-bit PCM convenience for AudioRecord. */
    fun process(pcm: ShortArray, length: Int): List<Episode> {
        val f = FloatArray(length) { pcm[it] / 32768f }
        return process(f, length)
    }

    fun flush(): List<Episode> {
        if (cur == null) return emptyList()
        return listOfNotNull(finish())
    }

    private fun step(frame: FloatArray): Episode? {
        val idx = frameIdx++
        var sum = 0.0; var all = 0.0; var band = 0.0
        for (s in frame) {
            val x = s.toDouble()
            sum += x * x
            val h = hp60.next(x); all += h * h
            val b = bandLp2.next(bandLp1.next(bandHp2.next(bandHp1.next(x)))); band += b * b
        }
        val db = 20 * log10(max(sqrt(sum / frame.size), 1e-10))
        val ratio = if (all > 0) band / all else 0.0
        lastDb = db

        val floor = floorDb
        if (floor == null) {
            calib.add(db)
            pushPreroll(frame)
            remember(db)
            if (calib.size >= calibFrames) {
                // Median is robust to a cough during calibration.
                floorDb = max(calib.sorted()[calib.size / 2], cfg.minFloorDb)
            }
            return null
        }

        val active = db >= floor + cfg.thresholdDb && (cfg.minSpeechRatio <= 0 || ratio >= cfg.minSpeechRatio)
        remember(db)
        if (idx % FLOOR_EVERY == 0L) updateFloor()

        val c = cur
        if (c == null) {
            recent.addLast(active)
            if (recent.size > cfg.startWindow) recent.removeFirst()
            val count = recent.count { it }
            if (count >= cfg.startActive) {
                val frames = ArrayList<FloatArray>(preroll)
                frames.add(frame)
                cur = Current(idx - preroll.size, frames, count, db)
                preroll.clear()
                recent.clear()
            } else {
                pushPreroll(frame)
            }
            return null
        }

        c.frames.add(frame)
        c.peakDb = max(c.peakDb, db)
        if (active) { c.active++; c.silentRun = 0 } else c.silentRun++
        return if (c.silentRun >= hangFrames || c.frames.size >= maxFrames) finish() else null
    }

    private fun remember(db: Double) {
        history[historyPos] = db
        historyPos = (historyPos + 1) % history.size
        if (historyCount < history.size) historyCount++
    }

    private fun updateFloor() {
        if (historyCount < calibFrames) return
        val sorted = history.copyOf(historyCount).also { it.sort() }
        val p = sorted[((historyCount - 1) * cfg.floorPercentile).roundToInt()]
        floorDb = max(p, cfg.minFloorDb)
    }

    private fun pushPreroll(frame: FloatArray) {
        preroll.addLast(frame)
        if (preroll.size > prerollMax) preroll.removeFirst()
    }

    private fun finish(): Episode? {
        val c = cur ?: return null
        cur = null
        recent.clear()
        preroll.clear()
        c.frames.takeLast(prerollMax).forEach(preroll::addLast)
        if (c.active < minActive) return null
        val audio = FloatArray(c.frames.size * frameLen)
        c.frames.forEachIndexed { i, f -> f.copyInto(audio, i * frameLen) }
        return Episode(
            startS = max(0L, c.startFrame) * frameLen.toDouble() / sampleRate,
            sampleRate = sampleRate,
            audio = audio,
            peakDb = c.peakDb,
            activeS = c.active / fps,
        )
    }
}

private const val FLOOR_EVERY = 10L // frames between floor updates (~0.3 s)
