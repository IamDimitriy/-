package sonnik.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Synthetic night sounds shared by the tests (same recipes as the Python and JS tests). */
class Signals(val sr: Int = 16000, seed: Int = 1) {
    private val rnd = Random(seed)

    fun noise(s: Double, level: Double = 0.002) = FloatArray((sr * s).toInt()) {
        (sqrt(-2 * ln(rnd.nextDouble().coerceAtLeast(1e-9))) * cos(2 * PI * rnd.nextDouble()) * level).toFloat()
    }

    /** Voiced-speech stand-in: 150 Hz pitch, harmonics shaped like a vowel, 4 syllables per second. */
    fun speech(s: Double, level: Double = 0.1): FloatArray {
        val raw = DoubleArray((sr * s).toInt()) { i ->
            val t = i.toDouble() / sr
            var v = 0.0
            for (k in 2 until 20) if (150.0 * k < sr / 2) v += sin(2 * PI * 150 * k * t) / (1 + abs(150.0 * k - 800) / 400)
            v * 0.5 * (1 + sin(2 * PI * 4 * t))
        }
        val peak = raw.maxOf { abs(it) }
        return FloatArray(raw.size) { (raw[it] / peak * level).toFloat() }
    }

    fun snore(s: Double, level: Double = 0.2) = FloatArray((sr * s).toInt()) { i ->
        (level * sin(2 * PI * 70 * i / sr) * (0.6 + 0.4 * sin(2 * PI * 30 * i / sr))).toFloat()
    }

    fun silence(s: Double) = FloatArray((sr * s).toInt())

    fun click(s: Double): FloatArray = FloatArray((sr * s).toInt()).also { it.fill(0.8f, 100, 140) }
}

operator fun FloatArray.plus(o: FloatArray) = FloatArray(size) { this[it] + o[it] }

fun cat(vararg parts: FloatArray): FloatArray {
    val out = FloatArray(parts.sumOf { it.size })
    var o = 0
    for (p in parts) { p.copyInto(out, o); o += p.size }
    return out
}

fun runDetector(audio: FloatArray, sr: Int = 16000, cfg: DetectorConfig = DetectorConfig(), chunk: Int = 1600): List<Episode> {
    val d = Detector(sr, cfg)
    val out = ArrayList<Episode>()
    var i = 0
    while (i < audio.size) {
        val n = minOf(chunk, audio.size - i)
        out += d.process(audio.copyOfRange(i, i + n))
        i += n
    }
    return out + d.flush()
}
