package sonnik.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import sonnik.core.Episode
import sonnik.core.Minute
import sonnik.core.SoundClass
import sonnik.core.SoundKind
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NightsTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private fun at(s: String) = LocalDateTime.parse(s)
    private fun ep(seconds: Double) = Episode(0.0, 16000, FloatArray((16000 * seconds).toInt()), -20.0, seconds)

    @Before fun clean() { Nights.root(ctx).deleteRecursively() }

    @Test fun savedClipsAreListedInTimeOrder() {
        val dir = Nights.dirFor(ctx, at("2026-10-09T00:00"))
        Nights.save(dir, at("2026-10-09T03:12:45"), ep(2.0))
        Nights.save(dir, at("2026-10-09T01:00:00"), ep(1.0))
        val night = Nights.list(ctx).single()
        assertEquals(at("2026-10-09T00:00"), night.start)
        assertEquals(listOf(at("2026-10-09T01:00:00"), at("2026-10-09T03:12:45")), night.clips.map { it.at })
        assertEquals(listOf(1.0, 2.0), night.clips.map { it.durationS })
    }

    @Test fun clipsInTheSameSecondDoNotOverwrite() {
        val dir = Nights.dirFor(ctx, at("2026-10-09T00:00"))
        Nights.save(dir, at("2026-10-09T02:00:00"), ep(1.0))
        Nights.save(dir, at("2026-10-09T02:00:00"), ep(2.0))
        assertEquals(2, Nights.list(ctx).single().clips.size)
    }

    @Test fun nightIsNamedAfterItsMorning() {
        Nights.dirFor(ctx, at("2026-10-08T23:30"))
        Nights.dirFor(ctx, at("2026-10-10T00:00"))
        val mornings = Nights.list(ctx).map { it.morning }
        assertEquals(listOf(LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-09")), mornings)
    }

    @Test fun newestNightComesFirst() {
        Nights.dirFor(ctx, at("2026-10-07T00:00"))
        Nights.dirFor(ctx, at("2026-10-09T00:00"))
        Nights.dirFor(ctx, at("2026-10-08T00:00"))
        assertEquals(listOf(9, 8, 7), Nights.list(ctx).map { it.start.dayOfMonth })
    }

    @Test fun foreignFilesAreIgnored() {
        val dir = Nights.dirFor(ctx, at("2026-10-09T00:00"))
        File(dir, "notes.txt").writeText("x")
        File(dir, "2026-10-09_01-00-00.wav.part").writeText("half written")
        File(Nights.root(ctx), "junk").mkdirs()
        File(Nights.root(ctx), "stray.wav").writeText("x")
        val nights = Nights.list(ctx)
        assertEquals(1, nights.size)
        assertTrue(nights.single().clips.isEmpty())
    }

    @Test fun categoryIsKeptInTheFileName() {
        val dir = Nights.dirFor(ctx, at("2026-10-09T00:00"))
        val f = Nights.save(dir, at("2026-10-09T02:00:00"), ep(1.0), SoundClass(SoundKind.MOVEMENT, "door"))
        assertEquals("2026-10-09_02-00-00__move-door.wav", f.name)
        Nights.save(dir, at("2026-10-09T02:30:00"), ep(1.0), SoundClass(SoundKind.SNORE))
        val clips = Nights.list(ctx).single().clips
        assertEquals(listOf(SoundClass(SoundKind.MOVEMENT, "door"), SoundClass(SoundKind.SNORE)), clips.map { it.sound })
        assertEquals("Скрип и шорох · дверь", clips[0].sound.title)
    }

    @Test fun clipsFromOlderVersionsAreSpeech() {
        val dir = Nights.dirFor(ctx, at("2026-10-09T00:00"))
        sonnik.core.Wav.write(File(dir, "2026-10-09_03-12-45.wav"), FloatArray(16000), 16000)
        sonnik.core.Wav.write(File(dir, "2026-10-09_03-12-45_1.wav"), FloatArray(16000), 16000)
        val night = Nights.list(ctx).single()
        assertEquals(2, night.phrases)
        assertEquals(0, night.sounds)
    }

    @Test fun quietClipsAreSavedLouder() {
        val dir = Nights.dirFor(ctx, at("2026-10-09T00:00"))
        val quiet = Episode(0.0, 16000, FloatArray(16000) { if (it % 2 == 0) 0.02f else -0.02f }, -30.0, 1.0)
        val f = Nights.save(dir, at("2026-10-09T03:00:00"), quiet)
        val r = sonnik.core.Wav.Reader(f.inputStream())
        val buf = ShortArray(10)
        r.read(buf)
        assertTrue(buf[0] / 32767f > 0.3f, "peak ${buf[0] / 32767f}")
    }

    @Test fun minuteStatisticsAreReadBack() {
        val dir = Nights.dirFor(ctx, at("2026-10-09T00:00"))
        Nights.appendMinute(dir, Minute(0, 0.0, 0.0, 1.0, 0.02))
        Nights.appendMinute(dir, Minute(1, 0.0, 30.0, 0.0, 0.1))
        Nights.appendMinute(dir, Minute(2, 2.5, 0.0, 0.0, 0.3))
        val night = Nights.list(ctx).single()
        assertEquals(listOf(0, 1, 2), night.minutes.map { it.index })
        assertEquals(1, night.summary.snoreMinutes)
        assertEquals(2.5, night.summary.speechS)
    }

    @Test fun deletingAClipAndANight() {
        val dir = Nights.dirFor(ctx, at("2026-10-09T00:00"))
        Nights.save(dir, at("2026-10-09T01:00:00"), ep(1.0))
        Nights.save(dir, at("2026-10-09T02:00:00"), ep(1.0))
        Nights.delete(Nights.list(ctx).single().clips.first())
        assertEquals(listOf(at("2026-10-09T02:00:00")), Nights.list(ctx).single().clips.map { it.at })
        Nights.delete(Nights.list(ctx).single())
        assertTrue(Nights.list(ctx).isEmpty())
    }
}
