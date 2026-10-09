package sonnik.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceTrimTest {
    private val s = Signals()
    private val sr = 16000

    @Test fun phraseIsCutOutOfLongNoise() {
        // 20 s of hiss, a 2 s phrase, 30 s of hiss: what a noisy room used to save whole.
        val clip = cat(s.noise(20.0, 0.01), s.noise(2.0, 0.01) + s.speech(2.0), s.noise(30.0, 0.01))
        val pieces = VoiceTrim.pieces(clip, sr)
        assertEquals(1, pieces.size)
        val p = pieces.single()
        val len = p.audio.size.toDouble() / sr
        assertTrue(len in 2.0..4.0, "length $len")
        assertTrue(p.fromS in 18.5..20.2, "starts at ${p.fromS}")
    }

    @Test fun phrasesFarApartBecomeSeparatePieces() {
        val clip = cat(s.noise(3.0), s.noise(2.0) + s.speech(2.0), s.noise(20.0), s.noise(1.5) + s.speech(1.5), s.noise(3.0))
        val pieces = VoiceTrim.pieces(clip, sr)
        assertEquals(2, pieces.size)
        assertTrue(pieces[1].fromS > pieces[0].fromS + 15)
    }

    @Test fun wordsWithShortPausesStayOnePhrase() {
        val clip = cat(s.noise(3.0), s.noise(1.0) + s.speech(1.0), s.noise(1.0), s.noise(1.0) + s.speech(1.0), s.noise(3.0))
        assertEquals(1, VoiceTrim.pieces(clip, sr).size)
    }

    @Test fun clipWithoutClearVoiceIsKeptWhole() {
        val clip = s.noise(10.0, 0.01)
        val pieces = VoiceTrim.pieces(clip, sr)
        assertEquals(1, pieces.size)
        assertEquals(clip.size, pieces.single().audio.size)
    }

    @Test fun marginsKeepTheFirstSyllable() {
        val clip = cat(s.noise(5.0), s.noise(2.0) + s.speech(2.0), s.noise(5.0))
        val p = VoiceTrim.pieces(clip, sr).single()
        assertTrue(p.fromS <= 4.6, "starts at ${p.fromS}: the margin before the phrase is kept")
        assertTrue(p.fromS + p.audio.size.toDouble() / sr >= 7.5, "the margin after the phrase is kept")
    }
}
