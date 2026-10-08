package sonnik.core

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RetentionTest {
    private val now = LocalDateTime.parse("2026-10-09T07:00")

    @Test fun phrasesAreNeverDeleted() {
        assertTrue(Retention.keep(SoundKind.SPEECH, now.minusDays(31), now))
        assertTrue(Retention.keep(SoundKind.SPEECH, now.minusYears(5), now))
    }

    @Test fun soundsAreKeptForThirtyDays() {
        for (kind in SoundKind.entries.filter { it != SoundKind.SPEECH }) {
            assertTrue(Retention.keep(kind, now.minusDays(1), now), "$kind")
            assertTrue(Retention.keep(kind, now.minusDays(30), now), "$kind, exactly 30 days")
            assertFalse(Retention.keep(kind, now.minusDays(30).minusSeconds(1), now), "$kind, a second older")
            assertFalse(Retention.keep(kind, now.minusDays(90), now), "$kind")
        }
    }

    @Test fun clipsFromTheFutureAreKept() {
        // The phone's clock was moved back; nothing is lost because of it.
        assertTrue(Retention.keep(SoundKind.SNORE, now.plusDays(2), now))
    }
}

class TrimTest {
    private fun ep(seconds: Double, activeS: Double = seconds) =
        Episode(12.5, 16000, FloatArray((16000 * seconds).toInt()) { it.toFloat() }, -18.0, activeS)

    @Test fun longEpisodeKeepsItsStart() {
        val long = ep(120.0)
        val t = long.trimmedTo(20.0)
        assertEquals(20.0, t.durationS)
        assertEquals(20.0, t.activeS)
        assertEquals(12.5, t.startS)
        assertEquals(16000, t.sampleRate)
        assertEquals(-18.0, t.peakDb)
        assertTrue(long.audio.copyOf(t.audio.size).contentEquals(t.audio))
        assertEquals(16000 * 120, long.audio.size, "the original is untouched")
    }

    @Test fun shortActivityStaysAsItWas() = assertEquals(3.0, ep(60.0, activeS = 3.0).trimmedTo(20.0).activeS)

    @Test fun shortEpisodeIsReturnedAsIs() {
        val short = ep(8.0)
        assertTrue(short.trimmedTo(20.0) === short)
        val exact = ep(20.0)
        assertTrue(exact.trimmedTo(20.0) === exact)
    }
}
