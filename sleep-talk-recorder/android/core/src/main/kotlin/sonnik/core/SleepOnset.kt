package sonnik.core

import java.time.Duration
import java.time.LocalDateTime

/**
 * When the sleepers fell asleep, so that talking before sleep is not kept as sleep talk.
 *
 * People often chat for an hour or two after the night's recording has started. Talking while
 * still awake pushes the moment of falling asleep back; after [quiet] without talk (or as soon as
 * there is snoring, or the user says "we are asleep") the sleepers are asleep and stay so for the
 * rest of the session. With [quiet] of zero everything counts.
 *
 * Talk is two sure phrases within [TALK] of each other. A lone phrase does not count: now and
 * then the network takes a noise for a voice, and such a noise every half hour must not keep
 * the whole night from being recorded.
 */
class SleepOnset(private val quiet: Duration, start: LocalDateTime) {
    private var lastSpeech = start
    /** The latest sure phrase heard while awake, talk or not. */
    private var lastPhrase: LocalDateTime? = null
    private var since: LocalDateTime? = null

    /** Why sleep was decided: after quiet, snoring, or the user's "we are asleep". */
    var reason: Reason? = null
        @Synchronized get
        private set

    enum class Reason(val text: String) { QUIET("тишина"), SNORING("храп"), BUTTON("нажали «Мы спим»") }

    /** Whether the sleepers are asleep at [at]; decides it once the quiet has lasted long enough. */
    @Synchronized
    fun asleep(at: LocalDateTime): Boolean {
        if (since == null && !at.isBefore(lastSpeech.plus(quiet))) {
            since = lastSpeech.plus(quiet)
            reason = Reason.QUIET
        }
        return since?.let { !at.isBefore(it) } ?: false
    }

    /** When they fell asleep, if they have by [at]. */
    @Synchronized
    fun asleepSince(at: LocalDateTime): LocalDateTime? = if (asleep(at)) since else null

    /** A sure phrase at [at]: while still awake and with another one close by, talk. */
    @Synchronized
    fun heardSpeech(at: LocalDateTime) {
        if (asleep(at)) return
        val prev = lastPhrase
        if (prev != null && Duration.between(prev, at).abs() <= TALK && at.isAfter(lastSpeech)) lastSpeech = at
        if (prev == null || at.isAfter(prev)) lastPhrase = at
    }

    @Synchronized
    fun heardSnoring(at: LocalDateTime) = fellAsleep(at, Reason.SNORING)

    /** The user says they are going to sleep now. */
    @Synchronized
    fun sleepNow(at: LocalDateTime) = fellAsleep(at, Reason.BUTTON)

    /** Whole minutes without talk at [at], for "quiet 7 of 20 min". */
    @Synchronized
    fun quietMinutes(at: LocalDateTime): Int = Duration.between(lastSpeech, at).toMinutes().coerceAtLeast(0).toInt()

    private fun fellAsleep(at: LocalDateTime, why: Reason) {
        val s = since
        if (s == null || at.isBefore(s)) {
            since = at
            reason = why
        }
    }

    companion object {
        /** Two sure phrases this close are talk. */
        val TALK: Duration = Duration.ofMinutes(2)
    }
}
