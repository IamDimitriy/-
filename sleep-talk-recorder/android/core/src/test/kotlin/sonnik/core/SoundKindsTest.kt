package sonnik.core

import java.time.LocalDateTime
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SoundLabelsTest {
    @Test fun labelsMapToCategories() {
        assertEquals(SoundClass(SoundKind.SPEECH), SoundLabels.classOf("Speech"))
        assertEquals(SoundClass(SoundKind.SPEECH, "whisper"), SoundLabels.classOf("Whispering"))
        assertEquals(SoundClass(SoundKind.SNORE), SoundLabels.classOf("Snoring"))
        assertEquals(SoundClass(SoundKind.COUGH), SoundLabels.classOf("Cough"))
        assertEquals(SoundClass(SoundKind.MOVEMENT, "creak"), SoundLabels.classOf("Creak"))
        assertEquals(SoundClass(SoundKind.MOVEMENT, "door"), SoundLabels.classOf("Door"))
        assertEquals(SoundClass(SoundKind.STREET, "car"), SoundLabels.classOf("Traffic noise, roadway noise"))
        assertEquals(SoundClass(SoundKind.STREET, "dog"), SoundLabels.classOf("Bark"))
        assertEquals(SoundClass(SoundKind.OTHER), SoundLabels.classOf("Didgeridoo"))
        assertNull(SoundLabels.classOf("Silence"))
    }

    @Test fun titlesAreRussian() {
        assertEquals("Скрип и шорох · дверь", SoundClass(SoundKind.MOVEMENT, "door").title)
        assertEquals("Улица · собака", SoundClass(SoundKind.STREET, "dog").title)
        assertEquals("Храп", SoundClass(SoundKind.SNORE).title)
    }

    @Test fun speechWinsOverBackground() {
        val c = SoundLabels.classify(mapOf("Vehicle" to 0.7f, "Speech" to 0.35f, "Silence" to 0.9f))
        assertEquals(SoundKind.SPEECH, c?.kind)
    }

    @Test fun bestLabelWinsWithoutSpeech() {
        assertEquals(SoundClass(SoundKind.MOVEMENT, "creak"),
            SoundLabels.classify(mapOf("Creak" to 0.5f, "Door" to 0.3f, "Speech" to 0.05f)))
        assertEquals(SoundClass(SoundKind.SNORE), SoundLabels.classify(mapOf("Snoring" to 0.8f, "Breathing" to 0.4f)))
    }

    @Test fun nothingConfidentMeansUnknown() {
        assertNull(SoundLabels.classify(mapOf("Creak" to 0.03f, "Silence" to 0.9f)))
        assertNull(SoundLabels.classify(emptyMap()))
    }

    @Test fun everyDetailHasATitle() {
        val labels = listOf("Whispering", "Shout", "Laughter", "Crying, sobbing", "Groan", "Humming", "Breathing", "Sneeze",
            "Hiccup", "Creak", "Rustle", "Walk, footsteps", "Door", "Knock", "Car", "Siren", "Motorcycle", "Train",
            "Aircraft", "Dog", "Bird", "Rain", "Wind", "Thunder", "Chatter", "Cat", "Song", "Television", "Telephone",
            "Clock", "Water tap, faucet", "Dishes, pots, and pans", "Mosquito", "Vehicle horn, car horn, honking")
        for (l in labels) {
            val c = SoundLabels.classOf(l)!!
            assertTrue(c.detail != null && c.detailTitle != null, "$l -> $c")
        }
    }
}

class HeuristicClassifierTest {
    private val s = Signals()

    @Test fun speechIsSpeech() =
        assertEquals(SoundKind.SPEECH, HeuristicClassifier.classify(cat(s.noise(2.0), s.noise(2.0) + s.speech(2.0), s.noise(2.0)), 16000).kind)

    @Test fun rhythmicSnoringIsSnoring() {
        val snoring = FloatArray(16000 * 12) { i ->
            val t = i / 16000.0
            if (t % 4.0 < 1.5) (0.25 * sin(2 * PI * 70 * t)).toFloat() else 0f
        } + s.noise(12.0)
        assertEquals(SoundKind.SNORE, HeuristicClassifier.classify(snoring, 16000).kind)
    }

    @Test fun aSingleSnoreIsSnoring() {
        val one = FloatArray(16000 * 3) { i ->
            val t = i / 16000.0
            if (t in 0.5..2.0) (0.25 * sin(2 * PI * 70 * t)).toFloat() else 0f
        } + s.noise(3.0)
        assertEquals(SoundKind.SNORE, HeuristicClassifier.classify(one, 16000).kind)
    }

    @Test fun aKnockIsOther() =
        assertEquals(SoundKind.OTHER, HeuristicClassifier.classify(cat(s.noise(2.0), s.noise(2.0) + s.click(2.0)), 16000).kind)

    @Test fun emptyIsOther() = assertEquals(SoundKind.OTHER, HeuristicClassifier.classify(FloatArray(10), 16000).kind)
}

class ClipPolicyTest {
    private fun at(s: String) = LocalDateTime.parse(s)

    @Test fun everyPhraseIsKept() {
        val p = ClipPolicy()
        repeat(50) { assertTrue(p.keep(SoundKind.SPEECH, at("2026-10-09T03:00").plusSeconds(it.toLong()))) }
    }

    @Test fun aFewSamplesOfEachKindSpreadOverTheNight() {
        val p = ClipPolicy()
        assertTrue(p.keep(SoundKind.SNORE, at("2026-10-09T03:00")))
        assertFalse(p.keep(SoundKind.SNORE, at("2026-10-09T03:10")), "too close to the last one")
        assertTrue(p.keep(SoundKind.SNORE, at("2026-10-09T03:20")))
        assertTrue(p.keep(SoundKind.MOVEMENT, at("2026-10-09T03:21")), "each kind has its own samples")
        assertTrue(p.keep(SoundKind.SNORE, at("2026-10-09T03:40")))
        assertFalse(p.keep(SoundKind.SNORE, at("2026-10-09T05:00")), "three of a kind are enough")
    }

    @Test fun unclearSpeechIsNotSampled() {
        val p = ClipPolicy(perKind = 1)
        repeat(5) { assertTrue(p.keep(SoundKind.UNSURE, at("2026-10-09T03:00").plusSeconds(it.toLong()))) }
    }
}

class NormalizeTest {
    @Test fun quietClipIsRaised() {
        val out = Wav.normalized(floatArrayOf(0.01f, -0.02f, 0.005f))
        assertEquals(0.4f, -out[1], 1e-6f) // capped at 20x
        val mid = Wav.normalized(floatArrayOf(0.1f, -0.07f))
        assertEquals(0.7f, mid[0], 1e-6f)
    }

    @Test fun loudClipIsUntouched() {
        val a = floatArrayOf(0.9f, -0.5f)
        assertTrue(Wav.normalized(a) === a)
    }
}

class CombineTest {
    @Test fun modelWinsByDefault() =
        assertEquals(SoundClass(SoundKind.STREET, "dog"), combine(SoundClass(SoundKind.STREET, "dog"), SoundClass(SoundKind.OTHER)))

    @Test fun rulesFillInWithoutAModel() = assertEquals(SoundClass(SoundKind.SNORE), combine(null, SoundClass(SoundKind.SNORE)))

    @Test fun rhythmBeatsALowRumbleLabel() =
        assertEquals(SoundKind.SNORE, combine(SoundClass(SoundKind.STREET, "car"), SoundClass(SoundKind.SNORE))?.kind)

    @Test fun speechIsNeverOverruled() =
        assertEquals(SoundKind.SPEECH, combine(SoundClass(SoundKind.SPEECH), SoundClass(SoundKind.SNORE))?.kind)

    /** The network heard only hiss: the rules must not turn it into a phrase or "other". */
    @Test fun noiseTheNetworkIgnoresIsDropped() {
        assertEquals(null, combine(null, SoundClass(SoundKind.SPEECH)))
        assertEquals(null, combine(null, SoundClass(SoundKind.OTHER)))
    }

    @Test fun steadyNoiseLabelsAreIgnored() {
        assertEquals(null, SoundLabels.classify(mapOf("White noise" to 0.9f, "Mechanical fan" to 0.5f, "Hum" to 0.3f)))
        assertEquals(null, SoundLabels.classOf("Air conditioning"))
        // The network's names for plain hiss (measured on white noise): loud, and nearly silent.
        assertEquals(null, SoundLabels.nonSpeech(mapOf("White noise" to 0.8f, "Waterfall" to 0.6f), minScore = 0.3f))
        assertEquals(null, SoundLabels.nonSpeech(mapOf("Silence" to 0.4f, "Ping" to 0.33f), minScore = 0.3f))
        // A fridge's hum: a tone the network takes for an instrument.
        assertEquals(null, SoundLabels.nonSpeech(mapOf("Sine wave" to 0.7f, "Music" to 0.5f, "Gong" to 0.4f), minScore = 0.3f))
    }
}
