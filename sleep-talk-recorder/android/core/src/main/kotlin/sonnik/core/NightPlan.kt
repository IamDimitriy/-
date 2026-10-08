package sonnik.core

import java.time.LocalDateTime

/**
 * When a recording session keeps clips and when it ends.
 *
 * The microphone may be switched on before the night begins (the user taps "start" in the
 * evening, or the alarm fires a minute early to let the detector learn the room's silence);
 * clips that end before [saveFrom] are thrown away.
 */
data class NightPlan(val saveFrom: LocalDateTime, val stopAt: LocalDateTime) {

    fun keeps(clipStart: LocalDateTime, clipEnd: LocalDateTime): Boolean =
        clipEnd >= saveFrom && clipStart < stopAt

    fun isRecording(now: LocalDateTime) = now >= saveFrom

    companion object {
        /**
         * [now] = the user asked to record from this moment; otherwise wait for the window.
         * Either way the session ends at the end of the current or next night window.
         */
        fun make(window: NightWindow, now: LocalDateTime, startNow: Boolean): NightPlan {
            val saveFrom = if (startNow || window.contains(now)) now else window.nextStart(now)
            return NightPlan(saveFrom, window.endFor(now))
        }
    }
}
