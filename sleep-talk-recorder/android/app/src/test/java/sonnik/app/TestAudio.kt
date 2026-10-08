package sonnik.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Synthetic night: quiet room noise, speech-like phrases and snoring (same recipes as :core tests). */
object TestAudio {
    const val RATE = 16000
    private val rnd = Random(7)

    fun noise(s: Double, level: Double = 0.002) = FloatArray((RATE * s).toInt()) {
        (sqrt(-2 * ln(rnd.nextDouble().coerceAtLeast(1e-9))) * cos(2 * PI * rnd.nextDouble()) * level).toFloat()
    }

    fun speech(s: Double, level: Double = 0.1): FloatArray {
        val raw = DoubleArray((RATE * s).toInt()) { i ->
            val t = i.toDouble() / RATE
            var v = 0.0
            for (k in 2 until 20) v += sin(2 * PI * 150 * k * t) / (1 + abs(150.0 * k - 800) / 400)
            v * 0.5 * (1 + sin(2 * PI * 4 * t))
        }
        val peak = raw.maxOf { abs(it) }
        return FloatArray(raw.size) { (raw[it] / peak * level).toFloat() }
    }

    fun snore(s: Double, level: Double = 0.2) = FloatArray((RATE * s).toInt()) { i ->
        (level * sin(2 * PI * 70 * i / RATE) * (0.6 + 0.4 * sin(2 * PI * 30 * i / RATE))).toFloat()
    }

    /** Snoring: a low rumble on every breath, 1.5 s of sound every 4 s. */
    fun snoring(s: Double) = FloatArray((RATE * s).toInt()) { i ->
        val t = i.toDouble() / RATE
        if (t % 4.0 < 1.5) (0.25 * sin(2 * PI * 70 * t) * (0.6 + 0.4 * sin(2 * PI * 28 * t))).toFloat() else 0f
    }.let { mix(it, noise(s)) }

    /** Tossing and turning: half a second of rustle every second (not speech, not snoring). */
    fun restless(s: Double) = FloatArray((RATE * s).toInt()) { i ->
        if ((i / (RATE / 2)) % 2 == 0) (rnd.nextDouble(-1.0, 1.0) * 0.05).toFloat() else 0f
    }.let { mix(it, noise(s)) }

    fun mix(a: FloatArray, b: FloatArray) = FloatArray(a.size) { a[it] + b[it] }

    fun cat(vararg parts: FloatArray): ShortArray {
        val out = ShortArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) for (v in p) out[o++] = (v.coerceIn(-1f, 1f) * 32767).toInt().toShort()
        return out
    }

    /** 10 s of room, a phrase, snoring, another phrase: two phrases and one snoring clip. */
    fun night(): ShortArray = cat(
        noise(10.0), mix(noise(2.0), speech(2.0)), noise(8.0),
        snoring(12.0), noise(6.0),
        mix(noise(2.0), speech(2.0)), noise(6.0),
    )
}

/** Feeds prepared samples as fast as they are read; returns -1 at the end unless [loop]. */
class FakeInput(private val samples: ShortArray, private val loop: Boolean = false) : AudioInput {
    override val sampleRate = TestAudio.RATE
    private var pos = 0
    @Volatile var closed = false

    override fun start() = true

    override fun read(buf: ShortArray): Int {
        if (pos >= samples.size) {
            if (!loop) return -1
            pos = 0
            Thread.sleep(5)
        }
        val n = minOf(buf.size, samples.size - pos)
        samples.copyInto(buf, 0, pos, pos + n)
        pos += n
        return n
    }

    override fun close() { closed = true }
}
