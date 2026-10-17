package sonnik.core

import java.time.Duration
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SleepOnsetTest {
    private fun at(s: String) = LocalDateTime.parse("2026-10-17T$s")
    private fun onset(min: Long = 20) = SleepOnset(Duration.ofMinutes(min), at("00:00"))

    @Test fun talkingBeforeSleepKeepsUsAwake() {
        val g = onset()
        // Chatting from ten past midnight to half past one, slowly: a phrase every two minutes.
        var t = at("00:10")
        while (!t.isAfter(at("01:30"))) { g.heardSpeech(t); t = t.plusMinutes(2) }
        assertFalse(g.asleep(at("01:45")))
        assertTrue(g.asleep(at("01:50")))
        assertEquals(at("01:50"), g.asleepSince(at("02:40")))
        assertEquals(SleepOnset.Reason.QUIET, g.reason)
    }

    @Test fun aLonePhraseIsNotTalk() {
        // A noise taken for a voice now and then must not keep the night from starting.
        val g = onset()
        g.heardSpeech(at("00:05"))
        g.heardSpeech(at("00:15"))
        assertTrue(g.asleep(at("00:20")))
    }

    @Test fun twoPhrasesCloseTogetherAreTalk() {
        val g = onset()
        g.heardSpeech(at("00:15"))
        g.heardSpeech(at("00:16:30"))
        assertFalse(g.asleep(at("00:30")))
        assertEquals(at("00:36:30"), g.asleepSince(at("01:00")))
    }

    @Test fun sleepTalkAfterFallingAsleepDoesNotWakeUs() {
        val g = onset()
        g.heardSpeech(at("01:00"))
        g.heardSpeech(at("01:01"))
        assertTrue(g.asleep(at("02:41")))
        g.heardSpeech(at("02:41"))
        assertTrue(g.asleep(at("02:45")), "a phrase in sleep is what we are here for")
    }

    @Test fun snoringMeansAsleep() {
        val g = onset(60)
        g.heardSpeech(at("00:30"))
        g.heardSnoring(at("00:35"))
        assertTrue(g.asleep(at("00:36")))
        assertFalse(g.asleep(at("00:34")))
        assertEquals(SleepOnset.Reason.SNORING, g.reason)
    }

    @Test fun theButtonSaysWeSleepNow() {
        val g = onset(30)
        g.sleepNow(at("00:05"))
        assertTrue(g.asleep(at("00:06")))
        assertEquals(SleepOnset.Reason.BUTTON, g.reason)
    }

    @Test fun zeroQuietMeansFromTheStart() = assertTrue(onset(0).asleep(at("00:00")))

    @Test fun quietMinutesForTheStatus() {
        val g = onset()
        g.heardSpeech(at("00:09"))
        g.heardSpeech(at("00:10"))
        assertEquals(7, g.quietMinutes(at("00:17:30")))
    }
}
