package sonnik.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WavTest {
    @Test fun headerDescribes16BitMono() {
        val b = ByteBuffer.wrap(Wav.encode(FloatArray(100), 16000)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(b.array(), 0, 4))
        assertEquals("WAVE", String(b.array(), 8, 4))
        assertEquals(1, b.getShort(22).toInt())
        assertEquals(16000, b.getInt(24))
        assertEquals(16, b.getShort(34).toInt())
        assertEquals(200, b.getInt(40))
        assertEquals(44 + 200, b.array().size)
    }

    @Test fun clipsLoudSamplesInsteadOfWrapping() {
        val bytes = Wav.encode(floatArrayOf(2f, -2f), 8000)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(32767, b.getShort(44).toInt())
        assertEquals(-32767, b.getShort(46).toInt())
    }

    @Test fun writeLeavesNoPartialFile() {
        val dir = createTempDir()
        val f = File(dir, "a.wav")
        Wav.write(f, FloatArray(16000 * 3), 16000)
        assertEquals(3.0, Wav.durationS(f), 1e-6)
        assertEquals(listOf("a.wav"), dir.list()!!.toList())
        dir.deleteRecursively()
    }

    @Test fun readerRoundTrips() {
        val audio = Signals().speech(0.5)
        val r = Wav.Reader(ByteArrayInputStream(Wav.encode(audio, 16000)))
        assertEquals(16000, r.sampleRate)
        val out = ShortArray(audio.size + 10)
        assertEquals(audio.size, r.read(out))
        assertEquals(-1, r.read(out))
        for (i in audio.indices step 101) assertTrue(abs(out[i] / 32767f - audio[i]) < 1e-3)
    }

    @Test fun readerSkipsExtraChunksAndMixesStereo() {
        val o = ByteArrayOutputStream()
        val le = { n: Int, size: Int -> ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply { if (size == 2) putShort(n.toShort()) else putInt(n) }.array() }
        val data = ByteArrayOutputStream().apply {
            write(le(1000, 2)); write(le(3000, 2)) // frame 1: L=1000 R=3000
            write(le(-200, 2)); write(le(200, 2)) // frame 2
        }.toByteArray()
        val list = "INFOISFT\u0003\u0000\u0000\u0000abc".toByteArray(Charsets.ISO_8859_1) // odd size -> padded
        o.write("RIFF".toByteArray()); o.write(le(0, 4)); o.write("WAVE".toByteArray())
        o.write("LIST".toByteArray()); o.write(le(list.size, 4)); o.write(list); o.write(0)
        o.write("fmt ".toByteArray()); o.write(le(16, 4))
        o.write(le(1, 2)); o.write(le(2, 2)); o.write(le(22050, 4)); o.write(le(22050 * 4, 4)); o.write(le(4, 2)); o.write(le(16, 2))
        o.write("data".toByteArray()); o.write(le(data.size, 4)); o.write(data)
        val r = Wav.Reader(ByteArrayInputStream(o.toByteArray()))
        assertEquals(22050, r.sampleRate)
        assertEquals(2, r.channels)
        val out = ShortArray(8)
        assertEquals(2, r.read(out))
        assertEquals(2000, out[0].toInt())
        assertEquals(0, out[1].toInt())
    }

    @Test fun readerRejectsNonWav() {
        assertFailsWith<IllegalArgumentException> { Wav.Reader(ByteArrayInputStream(ByteArray(64))) }
    }

    @Suppress("DEPRECATION")
    private fun createTempDir(): File = kotlin.io.createTempDir()
}
