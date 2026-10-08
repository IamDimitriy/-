package sonnik.core

import java.io.File
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
}
