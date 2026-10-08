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

/** What the detector measured in one frame, for night statistics (snoring, restlessness). */
data class FrameInfo(
    /** Frame start from the beginning of the stream, seconds. */
    val timeS: Double,
    val durationS: Double,
    val db: Double,
    val floorDb: Double,
    /** Share of energy in the speech band. */
    val speechRatio: Double,
    /** Share of energy below 400 Hz, where snoring lives. */
    val lowRatio: Double,
    /** Counted as speech by the detector. */
    val speech: Boolean,
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

    // Spectrum of each frame (Hann window, zero-padded FFT), the same measure as the Python detector.
    private val spectrum = Spectrum(frameLen, sampleRate)

    /** Called for every frame once the room's silence is known. */
    var onFrame: ((FrameInfo) -> Unit)? = null

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
        var sum = 0.0
        for (s in frame) sum += s.toDouble() * s
        spectrum.analyse(frame)
        val all = spectrum.energy(60.0, Double.MAX_VALUE)
        val band = spectrum.energy(cfg.bandLowHz, cfg.bandHighHz)
        val low = spectrum.energy(60.0, LOW_BAND_HZ)
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
        onFrame?.invoke(
            FrameInfo(
                timeS = idx * frameLen.toDouble() / sampleRate, durationS = frameLen.toDouble() / sampleRate,
                db = db, floorDb = floor, speechRatio = ratio,
                lowRatio = if (all > 0) low / all else 0.0,
                // Speech for the statistics, whatever the detector is set to catch.
                speech = db >= floor + cfg.thresholdDb && ratio >= SPEECH_RATIO,
            )
        )

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

private const val LOW_BAND_HZ = 400.0
private const val SPEECH_RATIO = 0.45
private const val FLOOR_EVERY = 10L // frames between floor updates (~0.3 s)

/** Power spectrum of one frame; energy() sums it over a frequency range. */
internal class Spectrum(private val frameLen: Int, private val sampleRate: Int) {
    private val n = Integer.highestOneBit(frameLen - 1).shl(1).coerceAtLeast(2)
    private val window = DoubleArray(frameLen) { 0.5 - 0.5 * cos(2 * PI * it / (frameLen - 1).coerceAtLeast(1)) }
    private val re = DoubleArray(n)
    private val im = DoubleArray(n)
    private val power = DoubleArray(n / 2 + 1)
    private val cosT = DoubleArray(n / 2) { cos(2 * PI * it / n) }
    private val sinT = DoubleArray(n / 2) { -sin(2 * PI * it / n) }
    private val hzPerBin = sampleRate.toDouble() / n

    fun analyse(frame: FloatArray) {
        for (i in 0 until n) {
            re[i] = if (i < frameLen) frame[i] * window[i] else 0.0
            im[i] = 0.0
        }
        fft()
        for (k in power.indices) power[k] = re[k] * re[k] + im[k] * im[k]
    }

    /** Energy in (fromHz, toHz]. */
    fun energy(fromHz: Double, toHz: Double): Double {
        var e = 0.0
        for (k in power.indices) {
            val f = k * hzPerBin
            if (f > fromHz && f <= toHz) e += power[k]
        }
        return e
    }

    private fun fft() {
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val step = n / len
            for (start in 0 until n step len) {
                for (k in 0 until len / 2) {
                    val c = cosT[k * step]; val s = sinT[k * step]
                    val a = start + k; val b = a + len / 2
                    val xr = re[b] * c - im[b] * s
                    val xi = re[b] * s + im[b] * c
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                }
            }
            len = len shl 1
        }
    }
}
