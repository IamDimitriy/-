package sonnik.core

import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun at(s: String) = LocalDateTime.parse(s)

class NightWindowTest {
    private val w = NightWindow(LocalTime.MIDNIGHT, LocalTime.of(7, 0))

    @Test fun midnightWindow() {
        assertTrue(w.contains(at("2026-10-09T00:00")))
        assertTrue(w.contains(at("2026-10-09T06:59")))
        assertFalse(w.contains(at("2026-10-09T07:00")))
        assertFalse(w.contains(at("2026-10-08T23:59")))
        assertEquals(at("2026-10-09T00:00"), w.nextStart(at("2026-10-08T22:10")))
        assertEquals(at("2026-10-10T00:00"), w.nextStart(at("2026-10-09T00:00")))
        assertEquals(at("2026-10-09T07:00"), w.endFor(at("2026-10-09T03:00")))
        assertEquals(at("2026-10-09T07:00"), w.endFor(at("2026-10-08T22:00")))
    }

    @Test fun windowAcrossMidnight() {
        val n = NightWindow(LocalTime.of(23, 30), LocalTime.of(7, 0))
        assertTrue(n.contains(at("2026-10-08T23:45")))
        assertTrue(n.contains(at("2026-10-09T03:00")))
        assertFalse(n.contains(at("2026-10-09T12:00")))
        assertEquals(at("2026-10-09T07:00"), n.endFor(at("2026-10-08T23:45")))
        assertEquals(at("2026-10-09T07:00"), n.endFor(at("2026-10-09T03:00")))
        assertEquals(at("2026-10-10T07:00"), n.endFor(at("2026-10-09T12:00")))
        assertEquals(at("2026-10-09T23:30"), n.nextStart(at("2026-10-09T03:00")))
    }

    @Test fun daytimeWindowForNaps() {
        val nap = NightWindow(LocalTime.of(14, 0), LocalTime.of(15, 30))
        assertTrue(nap.contains(at("2026-10-09T14:10")))
        assertFalse(nap.contains(at("2026-10-09T16:00")))
        assertEquals(at("2026-10-09T15:30"), nap.endFor(at("2026-10-09T10:00")))
        assertEquals(at("2026-10-10T15:30"), nap.endFor(at("2026-10-09T16:00")))
    }

    @Test fun equalStartAndEndMeansAllDay() {
        val all = NightWindow(LocalTime.of(8, 0), LocalTime.of(8, 0))
        assertTrue(all.contains(at("2026-10-09T03:00")))
        assertEquals(at("2026-10-10T08:00"), all.endFor(at("2026-10-09T08:00")))
    }

    @Test fun endIsAlwaysAfterNow() {
        var t = at("2026-10-08T00:00")
        repeat(24 * 4) {
            for (window in listOf(w, NightWindow(LocalTime.of(22, 15), LocalTime.of(6, 45)))) {
                val end = window.endFor(t)
                assertTrue(end.isAfter(t), "$window at $t ends $end")
                assertTrue(window.nextStart(t).isAfter(t))
            }
            t = t.plusMinutes(15)
        }
    }
}

class NightPlanTest {
    private val w = NightWindow(LocalTime.MIDNIGHT, LocalTime.of(7, 0))

    @Test fun eveningStartWaitsForMidnight() {
        val p = NightPlan.make(w, at("2026-10-08T22:30"), startNow = false)
        assertEquals(at("2026-10-09T00:00"), p.saveFrom)
        assertEquals(at("2026-10-09T07:00"), p.stopAt)
        assertFalse(p.isRecording(at("2026-10-08T23:59")))
        assertTrue(p.isRecording(at("2026-10-09T00:00")))
    }

    @Test fun alarmOneMinuteEarlyWaitsForMidnight() {
        val p = NightPlan.make(w, at("2026-10-08T23:59"), startNow = false)
        assertEquals(at("2026-10-09T00:00"), p.saveFrom)
    }

    @Test fun lateAlarmStartsAtOnce() {
        val p = NightPlan.make(w, at("2026-10-09T00:07"), startNow = false)
        assertEquals(at("2026-10-09T00:07"), p.saveFrom)
        assertEquals(at("2026-10-09T07:00"), p.stopAt)
    }

    @Test fun startNowInTheEveningRecordsUntilMorning() {
        val p = NightPlan.make(w, at("2026-10-08T22:30"), startNow = true)
        assertEquals(at("2026-10-08T22:30"), p.saveFrom)
        assertEquals(at("2026-10-09T07:00"), p.stopAt)
    }

    @Test fun keepsOnlyClipsInsideThePlan() {
        val p = NightPlan(at("2026-10-09T00:00"), at("2026-10-09T07:00"))
        assertFalse(p.keeps(at("2026-10-08T23:58:00"), at("2026-10-08T23:58:05")))
        assertTrue(p.keeps(at("2026-10-08T23:59:58"), at("2026-10-09T00:00:03")), "phrase crossing midnight")
        assertTrue(p.keeps(at("2026-10-09T03:00"), at("2026-10-09T03:00:04")))
        assertFalse(p.keeps(at("2026-10-09T07:00"), at("2026-10-09T07:00:04")))
    }
}
