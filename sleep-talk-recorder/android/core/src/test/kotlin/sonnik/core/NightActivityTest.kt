package sonnik.core

import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val SR = 16000

private fun minutesOf(audio: FloatArray, meter: ActivityMeter = ActivityMeter()): List<Minute> {
    val d = Detector(SR)
    val out = ArrayList<Minute>()
    d.onFrame = { f -> meter.add(f)?.let(out::add) }
    var i = 0
    while (i < audio.size) {
        val n = minOf(1600, audio.size - i)
        d.process(audio.copyOfRange(i, i + n))
        i += n
    }
    d.flush()
    meter.flush()?.let(out::add)
    return out
}

/** Snoring: a low rumble on every breath out, here 1.5 s of sound every 4 s. */
private fun snoring(s: Signals, seconds: Double): FloatArray {
    val n = (SR * seconds).toInt()
    return FloatArray(n) { i ->
        val t = i.toDouble() / SR
        val on = (t % 4.0) < 1.5
        if (on) (0.25 * sin(2 * PI * 70 * t) * (0.6 + 0.4 * sin(2 * PI * 28 * t))).toFloat() else 0f
    } + s.noise(seconds)
}

private fun thump(s: Signals, seconds: Double): FloatArray = FloatArray((SR * seconds).toInt()) { i ->
    val t = i.toDouble() / SR
    if (t in 20.0..20.8) (0.3 * sin(2 * PI * 60 * t)).toFloat() else 0f
} + s.noise(seconds)

class NightActivityTest {
    private val s = Signals()

    @Test fun quietMinuteIsCalm() {
        val m = minutesOf(s.noise(120.0))
        assertEquals(2, m.size)
        assertTrue(m.all { it.snoreS == 0.0 && it.speechS == 0.0 && it.activity < 0.05 }, m.toString())
    }

    @Test fun rhythmicSnoringIsCounted() {
        val m = minutesOf(cat(s.noise(60.0), snoring(s, 60.0), s.noise(60.0)))
        assertEquals(0.0, m[0].snoreS)
        assertTrue(m[1].snoreS in 15.0..30.0, "snore ${m[1].snoreS}")
        assertEquals(0.0, m[1].speechS)
        assertEquals(0.0, m[2].snoreS)
        assertEquals(1, NightSummary.of(m).snoreMinutes)
    }

    @Test fun aSingleThumpIsNoiseNotSnoring() {
        val m = minutesOf(cat(s.noise(60.0), thump(s, 60.0)))
        assertEquals(0.0, m[1].snoreS)
        assertTrue(m[1].noiseS > 0.3, "noise ${m[1].noiseS}")
    }

    @Test fun speechIsCountedAsSpeech() {
        val m = minutesOf(cat(s.noise(60.0), s.noise(20.0), s.noise(3.0) + s.speech(3.0), s.noise(37.0)))
        assertTrue(m[1].speechS >= 1.0, "speech ${m[1].speechS}")
        assertEquals(0.0, m[1].snoreS)
        assertTrue(m[1].activity > m[0].activity)
    }

    @Test fun framesBeforeTheNightAreIgnored() {
        val m = minutesOf(cat(s.noise(30.0), snoring(s, 30.0), s.noise(60.0)), ActivityMeter(startS = 60.0))
        assertEquals(listOf(0), m.map { it.index })
        assertEquals(0.0, m[0].snoreS)
    }

    @Test fun csvRoundTrip() {
        val m = Minute(42, 1.5, 22.3, 4.0, 0.125)
        assertEquals(m, Minute.fromCsv(m.toCsv()))
        assertEquals(null, Minute.fromCsv("garbage"))
        assertEquals(null, Minute.fromCsv(Minute.CSV_HEADER))
    }
}

class SmartWakeTest {
    private val alarm = LocalDateTime.parse("2026-10-09T07:00")
    private fun calm(n: Int, from: Int = 0) = (from until from + n).map { Minute(it, 0.0, 0.0, 0.0, 0.02) }
    private fun restless(n: Int, from: Int) = (from until from + n).map { Minute(it, 0.0, 0.0, 5.0, 0.3) }
    private fun at(s: String) = LocalDateTime.parse(s)

    @Test fun neverBeforeTheWindow() {
        val night = calm(300) + restless(3, 300)
        assertFalse(SmartWake.shouldWake(at("2026-10-09T06:20"), alarm, 30, night))
    }

    @Test fun restlessMinutesInTheWindowWake() {
        val night = calm(400) + restless(3, 400)
        assertTrue(SmartWake.shouldWake(at("2026-10-09T06:40"), alarm, 30, night))
    }

    @Test fun deepSleepWaitsForTheAlarmTime() {
        val night = calm(420)
        assertFalse(SmartWake.shouldWake(at("2026-10-09T06:50"), alarm, 30, night))
        assertTrue(SmartWake.shouldWake(at("2026-10-09T07:00"), alarm, 30, night))
    }

    @Test fun talkingCountsAsLightSleep() {
        val night = calm(400) + listOf(Minute(400, 2.0, 0.0, 0.0, 0.05)) + calm(2, 401)
        assertTrue(SmartWake.shouldWake(at("2026-10-09T06:45"), alarm, 30, night))
    }

    @Test fun aRestlessNightNeedsMoreThanUsual() {
        // When the whole night is noisy (a fan, a partner), only a clear jump wakes early.
        val night = (0 until 400).map { Minute(it, 0.0, 0.0, 3.0, 0.2) }
        assertFalse(SmartWake.shouldWake(at("2026-10-09T06:45"), alarm, 30, night))
        assertTrue(SmartWake.shouldWake(at("2026-10-09T06:45"), alarm, 30, night + (400 until 403).map { Minute(it, 0.0, 0.0, 30.0, 0.6) }))
    }

    @Test fun snoringIsNotLightSleep() {
        val night = calm(400) + (400 until 403).map { Minute(it, 0.0, 25.0, 0.0, 0.05) }
        assertFalse(SmartWake.shouldWake(at("2026-10-09T06:45"), alarm, 30, night))
    }

    @Test fun nextAlarmIsAfterTheStart() {
        assertEquals(at("2026-10-09T07:00"), SmartWake.nextAlarm(at("2026-10-08T23:30"), LocalTime.of(7, 0)))
        assertEquals(at("2026-10-09T07:00"), SmartWake.nextAlarm(at("2026-10-09T00:00"), LocalTime.of(7, 0)))
        assertEquals(at("2026-10-10T07:00"), SmartWake.nextAlarm(at("2026-10-09T07:00"), LocalTime.of(7, 0)))
    }
}
