package sonnik.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HearingTest {
    private val other = SoundClass(SoundKind.OTHER)
    private fun windows(vararg w: Map<String, Float>) = w.toList()

    @Test fun clearSpeechIsAPhrase() {
        val h = Heard(windows(mapOf("Speech" to 0.9f), mapOf("Speech" to 0.8f, "Silence" to 0.3f)), other)
        assertEquals(SoundKind.SPEECH, h.voice)
        assertEquals(SoundClass(SoundKind.SPEECH), h.voiceClass)
    }

    @Test fun oneSpikeInHissIsOnlyUnclear() {
        // What used to make "phrases" out of white noise: one window out of many.
        val h = Heard(windows(mapOf("White noise" to 0.6f, "Speech" to 0.55f), mapOf("White noise" to 0.6f), mapOf("Static" to 0.5f)), other)
        assertEquals(SoundKind.UNSURE, h.voice)
    }

    @Test fun hissIsNothing() {
        val h = Heard(windows(mapOf("White noise" to 0.6f, "Speech" to 0.1f), mapOf("Noise" to 0.5f, "Speech" to 0.05f)), other)
        assertNull(h.voice)
        assertNull(h.sound, "white noise is never kept")
    }

    @Test fun aWhisperKeepsItsName() {
        val h = Heard(windows(mapOf("Whispering" to 0.7f, "Speech" to 0.4f), mapOf("Whispering" to 0.6f)), other)
        assertEquals(SoundClass(SoundKind.SPEECH, "whisper"), h.voiceClass)
    }

    @Test fun aSureNonSpeechLabelIsASample() {
        val h = Heard(windows(mapOf("Knock" to 0.6f, "White noise" to 0.7f)), other)
        assertNull(h.voice)
        assertEquals(SoundClass(SoundKind.MOVEMENT, "knock"), h.sound)
    }

    @Test fun gruntsInSnoringAreNotAVoice() {
        // The network often hears grunts and sighs in snoring: a sample of snoring, not a phrase.
        val h = Heard(windows(mapOf("Snoring" to 0.5f, "Grunt" to 0.4f), mapOf("Snoring" to 0.6f, "Sigh" to 0.35f)), other)
        assertNull(h.voice)
        assertEquals(SoundKind.SNORE, h.sound?.kind)
    }

    @Test fun aWeakLabelIsNotASample() {
        assertNull(Heard(windows(mapOf("Creak" to 0.15f, "White noise" to 0.6f)), other).sound)
    }

    @Test fun snoringByRhythmUnderAnyLabel() {
        val h = Heard(windows(mapOf("White noise" to 0.6f)), SoundClass(SoundKind.SNORE))
        assertEquals(SoundKind.SNORE, h.sound?.kind)
    }

    @Test fun snoringTheNetworkHearsMeansAsleep() {
        assertTrue(Heard(windows(mapOf("Snoring" to 0.7f)), other).snoring)
        assertTrue(Heard(windows(mapOf("White noise" to 0.6f)), SoundClass(SoundKind.SNORE)).snoring, "a rhythm in plain noise")
        assertTrue(Heard(emptyList(), SoundClass(SoundKind.SNORE)).snoring, "rules alone")
    }

    @Test fun aLorryWithARhythmIsNotSleep() {
        val h = Heard(windows(mapOf("Truck" to 0.6f, "Engine" to 0.4f)), SoundClass(SoundKind.SNORE))
        assertEquals(SoundKind.SNORE, h.sound?.kind, "kept as a sample of what may be snoring")
        assertFalse(h.snoring)
    }

    @Test fun withoutTheNetworkTheRulesDecide() {
        assertEquals(SoundKind.SPEECH, Heard(emptyList(), SoundClass(SoundKind.SPEECH)).voice)
        assertNull(Heard(emptyList(), SoundClass(SoundKind.OTHER)).sound, "rules alone keep only snoring")
        assertEquals(SoundKind.SNORE, Heard(emptyList(), SoundClass(SoundKind.SNORE)).sound?.kind)
    }

    @Test fun topLabelsForTheReport() {
        val h = Heard(windows(mapOf("Speech" to 0.91f, "Silence" to 0.2f)), other)
        assertTrue(h.top().startsWith("Speech 0.91"), h.top())
    }
}
