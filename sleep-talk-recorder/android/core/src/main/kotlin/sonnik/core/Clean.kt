package sonnik.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Makes a night clip easy to hear: the room's hiss is turned down, then the voice is brought to a
 * comfortable level. For listening only: the saved recording stays as it was.
 */
object Clean {
    /** Denoised, then levelled; the same length as [audio]. */
    fun voice(audio: FloatArray, sampleRate: Int): FloatArray = level(denoise(audio, sampleRate), sampleRate)

    /**
     * Spectral gating. A clip has some of the room's noise around the voice: its spectrum is
     * measured on the quietest frames, and each frequency of every frame is turned down by how much
     * of it is that noise, by [maxCutDb] at most, so the voice keeps its sound and nothing warbles.
     * A clip without a quieter stretch (all voice, or one steady sound) is left as it is.
     */
    fun denoise(audio: FloatArray, sampleRate: Int, maxCutDb: Double = 15.0): FloatArray {
        val n = frameSize(sampleRate)
        val hop = n / 2
        if (audio.size < n) return audio
        // Mirrored at both ends, so that the first and last samples lie under two frames too.
        val frames = (audio.size + 2 * hop - n + hop - 1) / hop + 1
        if (frames < MIN_FRAMES) return audio
        fun x(i: Int): Double {
            var j = i - hop
            if (j < 0) j = -j
            if (j >= audio.size) j = 2 * (audio.size - 1) - j
            return audio[j.coerceIn(0, audio.size - 1)].toDouble()
        }
        // sqrt-Hann in and out: their product, a Hann window, adds up to 1 at half overlap.
        val win = DoubleArray(n) { sqrt(0.5 - 0.5 * cos(2 * PI * it / n)) }

        // 1. The room: the quietest tenth of the frames (the margins around a phrase are longer).
        val energy = DoubleArray(frames) { f ->
            var e = 0.0
            for (k in 0 until n) { val v = x(f * hop + k) * win[k]; e += v * v }
            e
        }
        val order = energy.indices.sortedBy { energy[it] }
        val quiet = order.take(max(MIN_NOISE_FRAMES, frames / 10))
        val quietEnergy = quiet.sumOf { energy[it] } / quiet.size
        // Nothing clearly louder than that (one steady sound): there is no room noise to tell apart.
        if (energy[order[frames * 9 / 10]] < quietEnergy * NOISE_MARGIN) return audio

        // 2. Its spectrum.
        val fft = Fft(n)
        val bins = n / 2 + 1
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        val noise = DoubleArray(bins)
        for (f in quiet) {
            for (k in 0 until n) { re[k] = x(f * hop + k) * win[k]; im[k] = 0.0 }
            fft.transform(re, im)
            for (k in 0 until bins) noise[k] += re[k] * re[k] + im[k] * im[k]
        }
        for (k in 0 until bins) noise[k] /= quiet.size

        // 3. Every frame with each frequency turned down by its share of noise (a Wiener gain on
        // the power averaged over a few frames, so the noise's own flicker does not slip through),
        // then added back up.
        val floor = 10.0.pow(-maxCutDb / 20)
        val power = DoubleArray(bins)
        val raw = DoubleArray(bins)
        val out = DoubleArray((frames - 1) * hop + n)
        for (f in 0 until frames) {
            for (k in 0 until n) { re[k] = x(f * hop + k) * win[k]; im[k] = 0.0 }
            fft.transform(re, im)
            for (k in 0 until bins) {
                val p = re[k] * re[k] + im[k] * im[k]
                power[k] = if (f == 0) p else SMOOTH * power[k] + (1 - SMOOTH) * p
                val snr = max(power[k] / max(OVER_SUBTRACT * noise[k], 1e-20) - 1, 0.0)
                raw[k] = max(floor, snr / (1 + snr))
            }
            for (k in 0 until n) {
                // Smoothed over neighbouring frequencies too: no warbling.
                val b = if (k < bins) k else n - k
                val g = (raw[max(0, b - 1)] + 2 * raw[b] + raw[min(bins - 1, b + 1)]) / 4
                re[k] *= g; im[k] *= g
            }
            fft.transform(re, im, inverse = true)
            for (k in 0 until n) out[f * hop + k] += re[k] * win[k]
        }
        return FloatArray(audio.size) { out[it + hop].toFloat() }
    }

    /**
     * Brings the voice to [targetDb] (RMS). It is measured on the loud frames rather than on the
     * single loudest sample, so one click no longer keeps a whisper quiet. At most [maxGainDb]
     * louder and never quieter; a soft limiter keeps the peaks below clipping.
     */
    fun level(audio: FloatArray, sampleRate: Int, targetDb: Double = -20.0, maxGainDb: Double = 30.0): FloatArray {
        val frame = max(1, sampleRate * 30 / 1000)
        val n = audio.size / frame
        if (n == 0) return audio
        val power = DoubleArray(n) { f ->
            var s = 0.0
            for (k in f * frame until (f + 1) * frame) s += audio[k].toDouble() * audio[k]
            s / frame
        }
        power.sort()
        // The voice: louder than the pauses between words, quieter than a click or two.
        val voice = power[(n * VOICE_PERCENTILE).toInt().coerceIn(0, n - 1)]
        if (voice <= 0.0) return audio
        val gain = 10.0.pow((targetDb - 10 * log10(voice)).coerceIn(0.0, maxGainDb) / 20)
        return FloatArray(audio.size) { limit(audio[it] * gain) }
    }

    private fun limit(x: Double): Float {
        val a = abs(x)
        if (a <= KNEE) return x.toFloat()
        return (sign(x) * (KNEE + (CEILING - KNEE) * tanh((a - KNEE) / (CEILING - KNEE)))).toFloat()
    }

    /** About 32 ms: a power of two for the FFT. */
    private fun frameSize(sampleRate: Int) = Integer.highestOneBit((sampleRate * 0.032).toInt().coerceAtLeast(16))

    private const val MIN_FRAMES = 8
    private const val MIN_NOISE_FRAMES = 4
    /** The loud frames must be this much above the quiet ones (in energy: 6 dB). */
    private const val NOISE_MARGIN = 4.0
    /** The noise is taken for this much more than measured: what is left of it does not flicker. */
    private const val OVER_SUBTRACT = 1.5
    /** How much of a frequency's power carries over to the next frame (16 ms later at 16 kHz). */
    private const val SMOOTH = 0.6
    private const val VOICE_PERCENTILE = 0.85
    /** The limiter leaves everything below [KNEE] alone and never goes above [CEILING]. */
    private const val KNEE = 0.7
    private const val CEILING = 0.95
}
