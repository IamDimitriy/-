package sonnik.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import sonnik.core.Wav
import java.io.File
import kotlin.math.max

/** Where the night's sound comes from: the microphone, or a file in tests and the demo. */
interface AudioInput : AutoCloseable {
    val sampleRate: Int

    /** Returns false if the source cannot be opened (for example the mic is taken). */
    fun start(): Boolean

    /** Fills [buf] with mono 16-bit samples; returns the count, 0 if nothing yet, -1 when the source ended. */
    fun read(buf: ShortArray): Int
}

class MicInput(override val sampleRate: Int = 16000) : AudioInput {
    private var rec: AudioRecord? = null
    private var deadSince = 0L

    @SuppressLint("MissingPermission") // the service checks RECORD_AUDIO before opening the mic
    override fun start(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = runCatching {
            // VOICE_RECOGNITION has no automatic gain or noise suppression, so the room's silence stays honest.
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(minBuf, sampleRate) * 2,
            )
        }.getOrNull() ?: return false
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return false
        }
        r.startRecording()
        rec = r
        return true
    }

    override fun read(buf: ShortArray): Int {
        val r = rec
        if (r == null) {
            // Lost earlier (see below): try to open it again every few seconds, all night if need be.
            SystemClock.sleep(100)
            if (SystemClock.elapsedRealtime() - deadSince >= REOPEN_MS) {
                deadSince = SystemClock.elapsedRealtime()
                if (start()) Log.i("Sonnik", "Microphone reopened")
            }
            return 0
        }
        val n = r.read(buf, 0, buf.size)
        if (n == AudioRecord.ERROR_DEAD_OBJECT) {
            // The audio system restarted (or another app took the mic for good): this recorder is
            // dead and would return the same error till morning. Open a new one.
            Log.w("Sonnik", "Microphone lost, reopening")
            close()
            deadSince = SystemClock.elapsedRealtime()
            if (start()) Log.i("Sonnik", "Microphone reopened")
            return 0
        }
        if (n < 0) SystemClock.sleep(100) // transient error, e.g. a phone call took the mic
        return max(n, 0)
    }

    override fun close() {
        rec?.let { runCatching { it.stop() }; it.release() }
        rec = null
    }

    private companion object {
        const val REOPEN_MS = 5_000L
    }
}

/**
 * Plays a WAV file as if it came from the microphone. [realTime] paces it like a live stream,
 * so the demo shows phrases appearing one by one.
 */
class WavFileInput(private val file: File, private val realTime: Boolean) : AudioInput {
    private var reader: Wav.Reader? = null
    private var samples = 0L
    private var startedAt = 0L

    override val sampleRate: Int
        get() = reader?.sampleRate ?: Wav.Reader(file.inputStream()).use { it.sampleRate }

    override fun start(): Boolean {
        reader = runCatching { Wav.Reader(file.inputStream()) }.getOrNull() ?: return false
        startedAt = SystemClock.elapsedRealtime()
        return true
    }

    override fun read(buf: ShortArray): Int {
        val r = reader ?: return -1
        val n = r.read(buf)
        if (n > 0) {
            samples += n
            if (realTime) {
                val due = startedAt + samples * 1000 / r.sampleRate
                val wait = due - SystemClock.elapsedRealtime()
                if (wait > 0) SystemClock.sleep(wait)
            }
        }
        return n
    }

    override fun close() {
        reader?.close()
        reader = null
    }
}
