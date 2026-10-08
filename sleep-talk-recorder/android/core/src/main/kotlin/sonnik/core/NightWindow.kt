package sonnik.core

import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The nightly recording window, e.g. 00:00–07:00. The end may be "earlier" than the start
 * (23:30–07:00), in which case the window crosses midnight.
 */
data class NightWindow(val start: LocalTime, val end: LocalTime) {

    /** Whether [now] falls inside a window. */
    fun contains(now: LocalDateTime): Boolean {
        val t = now.toLocalTime()
        return if (start < end) t >= start && t < end else t >= start || t < end
    }

    /** Start of the next window strictly after [now] (today or tomorrow). */
    fun nextStart(now: LocalDateTime): LocalDateTime {
        val today = now.toLocalDate().atTime(start)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    /** End of the window that is running at [now], or of the next one if none is running. */
    fun endFor(now: LocalDateTime): LocalDateTime {
        val begin = if (contains(now)) currentStart(now) else nextStart(now)
        val sameDay = begin.toLocalDate().atTime(end)
        return if (sameDay.isAfter(begin)) sameDay else sameDay.plusDays(1)
    }

    private fun currentStart(now: LocalDateTime): LocalDateTime {
        val today = now.toLocalDate().atTime(start)
        return if (!today.isAfter(now)) today else today.minusDays(1)
    }
}
