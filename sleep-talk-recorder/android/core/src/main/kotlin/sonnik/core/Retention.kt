package sonnik.core

import java.time.LocalDateTime

/**
 * Keeps the recordings from filling the phone. Phrases are what the app is for, so they stay
 * until the user deletes them; snoring and other sounds are only examples of what the night
 * was like, so they are saved short and removed after [OTHER_DAYS] days. The night's minute
 * statistics (and its graphs) are not clips and are never removed.
 */
object Retention {
    const val OTHER_DAYS = 30

    /** Non-speech clips are cut to this many seconds: the start is enough to tell what the sound was. */
    const val OTHER_MAX_S = 20.0

    /** Whether a clip recorded at [clipAt] is still kept at [now]. */
    fun keep(kind: SoundKind, clipAt: LocalDateTime, now: LocalDateTime): Boolean =
        kind == SoundKind.SPEECH || !clipAt.isBefore(now.minusDays(OTHER_DAYS.toLong()))
}

/** The first [maxS] seconds of the episode; the episode itself when it is no longer than that. */
fun Episode.trimmedTo(maxS: Double): Episode {
    if (durationS <= maxS) return this
    val n = (maxS * sampleRate).toInt().coerceIn(0, audio.size)
    return Episode(startS, sampleRate, audio.copyOf(n), peakDb, minOf(activeS, maxS))
}
