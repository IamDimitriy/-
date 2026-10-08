package sonnik.core

import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Wav {
    const val HEADER = 44

    fun encode(audio: FloatArray, sampleRate: Int): ByteArray {
        val buf = ByteBuffer.allocate(HEADER + audio.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        writeHeader(buf, audio.size, sampleRate)
        for (s in audio) buf.putShort((s.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        return buf.array()
    }

    fun write(file: File, audio: FloatArray, sampleRate: Int) {
        val tmp = File(file.parentFile, file.name + ".part")
        tmp.outputStream().use { it.write(encode(audio, sampleRate)) }
        tmp.renameTo(file)
    }

    /** Duration of a 16-bit mono WAV written by [encode], read from its header. */
    fun durationS(file: File): Double {
        if (file.length() < HEADER) return 0.0
        val head = ByteArray(HEADER)
        file.inputStream().use { it.read(head) }
        val b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        val rate = b.getInt(24)
        val dataLen = b.getInt(40)
        return if (rate > 0) dataLen / 2.0 / rate else 0.0
    }

    private fun writeHeader(b: ByteBuffer, samples: Int, rate: Int) {
        fun str(s: String) = s.forEach { b.put(it.code.toByte()) }
        str("RIFF"); b.putInt(36 + samples * 2); str("WAVE")
        str("fmt "); b.putInt(16); b.putShort(1); b.putShort(1)
        b.putInt(rate); b.putInt(rate * 2); b.putShort(2); b.putShort(16)
        str("data"); b.putInt(samples * 2)
    }

    /**
     * Streams 16-bit PCM from any WAV file as mono samples (channels are averaged).
     * Skips extra chunks such as LIST that other tools write before the audio.
     */
    class Reader(input: InputStream) : AutoCloseable {
        private val inp = DataInputStream(input.buffered())
        val sampleRate: Int
        val channels: Int
        private var remaining: Long

        init {
            val riff = ByteArray(12)
            inp.readFully(riff)
            require(String(riff, 0, 4, Charsets.US_ASCII) == "RIFF" && String(riff, 8, 4, Charsets.US_ASCII) == "WAVE") {
                "Not a WAV file"
            }
            var rate = 0
            var ch = 0
            var bits = 0
            while (true) {
                val id = ByteArray(4).also { inp.readFully(it) }.toString(Charsets.US_ASCII)
                val len = Integer.reverseBytes(inp.readInt()).toLong() and 0xffffffffL
                if (id == "fmt ") {
                    val fmt = ByteArray(len.toInt()).also { inp.readFully(it) }
                    val b = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                    ch = b.getShort(2).toInt()
                    rate = b.getInt(4)
                    bits = b.getShort(14).toInt()
                    if (len % 2 == 1L) inp.skipBytes(1)
                } else if (id == "data") {
                    remaining = len
                    break
                } else {
                    skipFully(len + len % 2)
                }
            }
            require(bits == 16) { "Only 16-bit WAV is supported, got $bits-bit" }
            require(ch >= 1 && rate > 0) { "Broken WAV header" }
            sampleRate = rate
            channels = ch
        }

        // InputStream.skipNBytes is missing on older Android versions.
        private fun skipFully(n: Long) {
            var left = n
            while (left > 0) {
                val s = inp.skip(left)
                if (s <= 0) { inp.readByte(); left-- } else left -= s
            }
        }

        /** Reads up to [out].size mono samples; returns how many, or -1 at the end. */
        fun read(out: ShortArray): Int {
            var n = 0
            try {
                while (n < out.size && remaining >= 2L * channels) {
                    var sum = 0
                    repeat(channels) { sum += java.lang.Short.reverseBytes(inp.readShort()).toInt() }
                    remaining -= 2L * channels
                    out[n++] = (sum / channels).toShort()
                }
            } catch (_: EOFException) {
                remaining = 0
            }
            return if (n == 0) -1 else n
        }

        override fun close() = inp.close()
    }
}
