package sonnik.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import sonnik.core.Episode
import sonnik.core.Wav
import java.io.File
import java.time.LocalDateTime
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CleanedTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Before fun clean() {
        Nights.root(ctx).deleteRecursively()
        File(ctx.cacheDir, "clear").deleteRecursively()
    }

    private fun read(f: File): FloatArray = Wav.Reader(f.inputStream()).use { r ->
        val out = ArrayList<Float>()
        val buf = ShortArray(4096)
        while (true) {
            val n = r.read(buf)
            if (n < 0) break
            for (i in 0 until n) out += buf[i] / 32768f
        }
        out.toFloatArray()
    }

    private fun rms(a: FloatArray, from: Int, to: Int) = sqrt((from until to).sumOf { a[it].toDouble() * a[it] } / (to - from))

    /** A whisper between two stretches of the room, and a click that kept it quiet when saved. */
    private fun whisper(): File {
        val rnd = Random(3)
        val audio = FloatArray(16000 * 3) { i ->
            val room = (rnd.nextDouble(-1.0, 1.0) * 0.0005).toFloat()
            if (i in 16000 until 32000) room + (0.008 * sin(2 * PI * 220 * i / 16000)).toFloat() else room
        }
        for (i in 8000 until 8040) audio[i] = 0.9f
        val dir = Nights.dirFor(ctx, LocalDateTime.parse("2026-10-09T00:00"))
        return Nights.save(dir, LocalDateTime.parse("2026-10-09T03:00:00"), Episode(0.0, 16000, audio, -40.0, 1.0))
    }

    @Test fun aLouderCopyIsMadeOnceAndTheRecordingIsKept() {
        val clip = whisper()
        val before = clip.readBytes()
        val copy = Cleaned.file(ctx, clip)
        assertTrue(copy.exists())
        assertTrue(copy.path.startsWith(ctx.cacheDir.path), copy.path)
        assertTrue(clip.readBytes().contentEquals(before), "the recording itself is never changed")
        val original = read(clip)
        val louder = read(copy)
        assertEquals(original.size, louder.size)
        assertTrue(rms(louder, 17000, 31000) > 3 * rms(original, 17000, 31000), "the whisper is louder")
        // Asked again, the same copy is played.
        val made = copy.lastModified()
        Thread.sleep(20)
        assertEquals(made, Cleaned.file(ctx, clip).lastModified())
    }

    @Test fun aDeletedClipTakesItsCopyAlong() {
        val clip = whisper()
        val copy = Cleaned.file(ctx, clip)
        Cleaned.forget(ctx, clip)
        assertFalse(copy.exists())
    }
}
