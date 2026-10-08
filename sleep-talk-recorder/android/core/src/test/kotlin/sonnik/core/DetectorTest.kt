package sonnik.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DetectorTest {
    private val s = Signals()

    @Test fun quietNightHasNoEpisodes() = assertEquals(0, runDetector(s.noise(60.0)).size)

    @Test fun speechIsCapturedWithPreroll() {
        val ev = runDetector(cat(s.noise(20.0), s.noise(3.0) + s.speech(3.0), s.noise(20.0)))
        assertEquals(1, ev.size)
        assertTrue(ev[0].startS in 18.0..20.5, "start ${ev[0].startS}")
        assertTrue(ev[0].durationS in 4.0..9.0, "duration ${ev[0].durationS}")
        assertTrue(ev[0].activeS >= 1.5, "active ${ev[0].activeS}")
    }

    @Test fun episodeAudioMatchesTheStream() {
        val audio = cat(s.noise(10.0), s.noise(2.0) + s.speech(2.0), s.noise(10.0))
        val ev = runDetector(audio).single()
        val start = (ev.startS * 16000).toInt()
        for (i in ev.audio.indices step 997) assertEquals(audio[start + i], ev.audio[i])
    }

    @Test fun twoPhrasesAreTwoEpisodes() {
        val p = { s.noise(2.0) + s.speech(2.0) }
        assertEquals(2, runDetector(cat(s.noise(10.0), p(), s.noise(15.0), p(), s.noise(10.0))).size)
    }

    @Test fun shortPauseKeepsOnePhrase() {
        val p = { s.noise(1.5) + s.speech(1.5) }
        assertEquals(1, runDetector(cat(s.noise(10.0), p(), s.noise(1.0), p(), s.noise(10.0))).size)
    }

    @Test fun snoringAndClicksAreIgnored() {
        val audio = cat(s.noise(10.0), s.noise(5.0) + s.snore(5.0), s.noise(5.0), s.noise(2.0) + s.click(2.0), s.noise(10.0))
        assertEquals(0, runDetector(audio).size)
    }

    @Test fun quietPhraseRightAfterSnoringIsCaught() {
        // Seen in the emulator demo: snoring used to raise the noise floor and hide the next phrase.
        val audio = cat(
            s.noise(15.0), s.noise(6.0) + s.snore(6.0, level = 0.3), s.noise(6.0),
            s.noise(1.5) + s.speech(1.5, level = 0.03), s.noise(10.0),
        )
        assertEquals(1, runDetector(audio).size)
    }

    @Test fun anySoundModeKeepsSnoring() {
        val audio = cat(s.noise(10.0), s.noise(5.0) + s.snore(5.0), s.noise(10.0))
        assertEquals(1, runDetector(audio, cfg = DetectorConfig(minSpeechRatio = 0.0)).size)
    }

    @Test fun lowerThresholdCatchesWhisper() {
        val audio = cat(s.noise(10.0), s.noise(2.0) + s.speech(2.0, level = 0.018), s.noise(10.0))
        assertEquals(0, runDetector(audio).size)
        assertEquals(1, runDetector(audio, cfg = DetectorConfig(thresholdDb = 4.0)).size)
    }

    @Test fun veryShortSoundIsDropped() {
        val audio = cat(s.noise(10.0), s.noise(0.25) + s.speech(0.25), s.noise(10.0))
        assertEquals(0, runDetector(audio).size)
    }

    @Test fun longMonologueIsSplitIntoBoundedClips() {
        val audio = cat(s.noise(5.0), s.noise(30.0) + s.speech(30.0), s.noise(5.0))
        val ev = runDetector(audio, cfg = DetectorConfig(maxEventS = 10.0))
        assertTrue(ev.size >= 3, "got ${ev.size}")
        assertTrue(ev.all { it.durationS <= 10.05 }, ev.map { it.durationS }.toString())
    }

    @Test fun digitalSilenceThenSpeech() {
        // A muted mic gives exact zeros; the floor is clamped so hiss after it does not trigger.
        val audio = cat(s.silence(5.0), s.noise(10.0, 0.0005), s.noise(2.0, 0.0005) + s.speech(2.0), s.noise(5.0, 0.0005))
        assertEquals(1, runDetector(audio).size)
    }

    @Test fun floorFollowsALouderRoom() {
        val audio = cat(s.noise(5.0, 0.001), s.noise(60.0, 0.01), s.noise(2.0, 0.01) + s.speech(2.0, 0.3), s.noise(10.0, 0.01))
        val d = Detector(16000)
        var n = 0
        var i = 0
        while (i < audio.size) {
            val c = minOf(1600, audio.size - i)
            n += d.process(audio.copyOfRange(i, i + c)).size
            i += c
        }
        n += d.flush().size
        assertEquals(1, n)
        assertTrue(d.floorDb!! > -45, "floor ${d.floorDb}")
    }

    @Test fun floorIsUnknownWhileCalibrating() {
        val d = Detector(16000)
        d.process(s.noise(1.0))
        assertNull(d.floorDb)
        d.process(s.noise(3.0))
        assertTrue(d.floorDb != null)
    }

    @Test fun worksAtOtherSampleRates() {
        for (rate in listOf(8000, 22050, 44100, 48000)) {
            val r = Signals(rate)
            val audio = cat(r.noise(10.0), r.noise(2.0) + r.speech(2.0), r.noise(10.0))
            assertEquals(1, runDetector(audio, sr = rate).size, "rate $rate")
        }
    }

    @Test fun chunkSizeDoesNotMatter() {
        val audio = cat(s.noise(10.0), s.noise(2.0) + s.speech(2.0), s.noise(10.0))
        val a = runDetector(audio, chunk = 1)
        val b = runDetector(audio, chunk = 48000)
        assertEquals(a.map { it.startS to it.audio.size }, b.map { it.startS to it.audio.size })
    }

    @Test fun pcmInputMatchesFloatInput() {
        val audio = cat(s.noise(10.0), s.noise(2.0) + s.speech(2.0), s.noise(5.0))
        val pcm = ShortArray(audio.size) { (audio[it] * 32767).toInt().toShort() }
        val d = Detector(16000)
        val ev = d.process(pcm, pcm.size) + d.flush()
        assertEquals(1, ev.size)
    }

    @Test fun flushClosesAnOpenEpisode() {
        val d = Detector(16000)
        d.process(cat(s.noise(10.0), s.noise(2.0) + s.speech(2.0)))
        assertTrue(d.inEpisode)
        assertEquals(1, d.flush().size)
        assertTrue(!d.inEpisode)
    }
}
