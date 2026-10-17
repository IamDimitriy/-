package sonnik.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CleanTest {
    private val s = Signals(seed = 5)
    private val rate = 16000

    private fun tone(seconds: Double, amplitude: Double, hz: Double = 220.0, sr: Int = rate) =
        FloatArray((sr * seconds).toInt()) { (amplitude * sin(2 * PI * hz * it / sr)).toFloat() }

    /** RMS in dBFS of [a] between [fromS] and [toS] seconds. */
    private fun db(a: FloatArray, fromS: Double, toS: Double, sr: Int = rate): Double {
        val from = (fromS * sr).toInt()
        val to = (toS * sr).toInt()
        var sum = 0.0
        for (i in from until to) sum += a[i].toDouble() * a[i]
        return 10 * log10(sum / (to - from) + 1e-20)
    }

    @Test fun nothingTurnedDownGivesTheSameSound() {
        // The frames, windows and their overlap add back up to the input exactly.
        val a = cat(s.noise(1.0, 0.01), s.noise(1.0, 0.01) + s.speech(1.0), s.noise(1.0, 0.01))
        val out = Clean.denoise(a, rate, maxCutDb = 0.0)
        assertEquals(a.size, out.size)
        for (i in a.indices) assertEquals(a[i], out[i], 1e-5f, "sample $i")
    }

    @Test fun hissIsTurnedDownAndTheVoiceKept() {
        val voice = cat(s.silence(1.0), s.speech(1.0, 0.1), s.silence(1.0))
        val a = cat(s.noise(1.0, 0.01), s.noise(1.0, 0.01), s.noise(1.0, 0.01)) + voice
        val out = Clean.denoise(a, rate)
        val before = db(a, 1.1, 1.9) - db(a, 0.1, 0.9)
        val after = db(out, 1.1, 1.9) - db(out, 0.1, 0.9)
        assertTrue(after - before >= 8, "voice over hiss: $before dB before, $after dB after")
        assertTrue(db(a, 0.1, 0.9) - db(out, 0.1, 0.9) >= 10, "the hiss alone is 10 dB quieter")
        assertTrue(abs(db(out, 1.1, 1.9) - db(voice, 1.1, 1.9)) < 2, "the voice keeps its level")
    }

    @Test fun aWhisperBehindAClickIsRaised() {
        // A whisper at -45 dBFS and one loud click: the old peak normalising could not raise it at all.
        val whisper = cat(s.noise(0.5, 0.0003), s.noise(2.0, 0.0003) + tone(2.0, 0.008), s.noise(0.5, 0.0003))
        for (i in 16000 until 16040) whisper[i] = 0.9f
        assertTrue(db(Wav.normalized(whisper), 1.1, 2.4) < -40, "the old way leaves it quiet")
        val out = Clean.voice(whisper, rate)
        assertTrue(db(out, 1.1, 2.4) > -26, "now it is heard: ${db(out, 1.1, 2.4)} dB")
        assertTrue(out.all { abs(it) <= 0.95f }, "and the click does not clip")
    }

    @Test fun loudSpeechIsLeftAtItsLevel() {
        val a = cat(s.noise(0.5, 0.001), s.noise(2.0, 0.001) + tone(2.0, 0.25), s.noise(0.5, 0.001))
        val out = Clean.voice(a, rate)
        assertEquals(db(a, 0.6, 2.4), db(out, 0.6, 2.4), 1.0)
    }

    @Test fun aSteadySoundWithoutPausesIsNotTakenForNoise() {
        val a = tone(3.0, 0.05)
        assertTrue(Clean.denoise(a, rate) === a)
    }

    @Test fun silenceShortClipsAndOtherRates() {
        assertEquals(0, Clean.voice(FloatArray(0), rate).size)
        assertEquals(100, Clean.voice(FloatArray(100), rate).size)
        val silent = Clean.voice(FloatArray(rate * 2), rate)
        assertTrue(silent.all { it == 0f })
        // The demo night and some phones record at 22 050 Hz.
        val other = Signals(sr = 22050, seed = 6)
        val a = cat(other.noise(1.0, 0.01), other.noise(1.0, 0.01) + other.speech(1.0, 0.05), other.noise(1.0, 0.01))
        val out = Clean.voice(a, 22050)
        assertEquals(a.size, out.size)
        assertTrue(out.none { it.isNaN() })
        assertTrue(db(out, 1.1, 1.9, 22050) > db(a, 1.1, 1.9, 22050), "a quiet voice is raised")
        assertTrue(sqrt(out.sumOf { it.toDouble() * it } / out.size) > 0)
    }
}
