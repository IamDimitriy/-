package sonnik.core

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Cuts the phrases out of a clip. The detector opens a clip at any sound a little above the
 * room's silence, so in a noisy room (a fan, the hiss of a cheap microphone) a short phrase can
 * sit inside a minute of noise. This keeps only the voiced parts, with a short margin so the
 * first and last syllables are not clipped; phrases far apart become separate pieces.
 */
object VoiceTrim {
    /** A piece of the clip starting [fromS] seconds after the clip's start. */
    class Piece(val fromS: Double, val audio: FloatArray)

    private const val FRAME_MS = 30
    private const val SPEECH_RATIO = 0.45

    /**
     * The voiced pieces of [audio]. Returns the whole clip as one piece when no voice stands out
     * from the noise (better a long clip than a lost phrase).
     */
    fun pieces(
        audio: FloatArray,
        sampleRate: Int,
        marginBeforeS: Double = 0.6,
        marginAfterS: Double = 0.9,
        joinGapS: Double = 3.0,
    ): List<Piece> {
        val frameLen = max(1, sampleRate * FRAME_MS / 1000)
        val n = audio.size / frameLen
        val whole = listOf(Piece(0.0, audio))
        if (n < 4) return whole
        val spectrum = Spectrum(frameLen, sampleRate)
        val db = DoubleArray(n)
        val ratio = DoubleArray(n)
        val frame = FloatArray(frameLen)
        for (i in 0 until n) {
            audio.copyInto(frame, 0, i * frameLen, (i + 1) * frameLen)
            var sum = 0.0
            for (s in frame) sum += s.toDouble() * s
            db[i] = 20 * log10(max(sqrt(sum / frameLen), 1e-10))
            spectrum.analyse(frame)
            val all = spectrum.energy(60.0, Double.MAX_VALUE)
            ratio[i] = if (all > 0) spectrum.energy(300.0, 3400.0) / all else 0.0
        }
        // The clip's own noise: most of a noisy clip is the room, not the voice.
        val floor = db.sorted()[n / 5]
        val frameS = frameLen.toDouble() / sampleRate
        // Try a clear margin over the noise first, then a smaller one for a quiet mumble.
        val voiced = listOf(6.0, 3.5).asSequence()
            .map { above -> BooleanArray(n) { db[it] >= floor + above && ratio[it] >= SPEECH_RATIO } }
            .firstOrNull { v -> v.count { it } * frameS >= MIN_VOICED_S }
            ?: return whole

        // Runs of voiced frames, joined when the pause between them is short.
        val runs = ArrayList<IntArray>() // [first frame, last frame]
        var i = 0
        val joinFrames = (joinGapS / frameS).toInt()
        while (i < n) {
            if (!voiced[i]) { i++; continue }
            var j = i
            while (j + 1 < n && voiced[j + 1]) j++
            val last = runs.lastOrNull()
            if (last != null && i - last[1] <= joinFrames) last[1] = j else runs += intArrayOf(i, j)
            i = j + 1
        }
        // A lone click is not a phrase: keep pieces with enough voice in them.
        val kept = runs.filter { r -> (r[0]..r[1]).count { voiced[it] } * frameS >= MIN_PIECE_VOICED_S }
            .ifEmpty { listOf(runs.maxBy { r -> (r[0]..r[1]).count { voiced[it] } }) }

        val before = (marginBeforeS * sampleRate).toInt()
        val after = (marginAfterS * sampleRate).toInt()
        val spans = ArrayList<IntArray>()
        for (r in kept) {
            val from = max(0, r[0] * frameLen - before)
            val to = min(audio.size, (r[1] + 1) * frameLen + after)
            val prev = spans.lastOrNull()
            if (prev != null && from <= prev[1]) prev[1] = max(prev[1], to) else spans += intArrayOf(from, to)
        }
        return spans.map { (from, to) -> Piece(from.toDouble() / sampleRate, audio.copyOfRange(from, to)) }
    }

    private const val MIN_VOICED_S = 0.25
    private const val MIN_PIECE_VOICED_S = 0.15
}
