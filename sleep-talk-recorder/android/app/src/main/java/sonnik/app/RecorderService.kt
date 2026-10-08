package sonnik.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import sonnik.core.Detector
import sonnik.core.DetectorConfig
import sonnik.core.Episode
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.max

/**
 * Foreground service that owns the microphone for the night.
 *
 * It must be started while the app is visible (from the app or from [WakeActivity]): Android
 * only lets a foreground service use the microphone if it was started from the foreground.
 */
class RecorderService : Service() {

    @Volatile private var running = false
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            running = false
            if (worker == null) finishSession(0)
            return START_NOT_STICKY
        }
        if (!running) begin(intent?.getBooleanExtra(EXTRA_NOW, false) ?: false)
        return START_NOT_STICKY
    }

    private fun begin(now: Boolean) {
        val prefs = Prefs(this)
        val zone = ZoneId.systemDefault()
        val nowDt = LocalDateTime.now()
        val window = prefs.window
        val saveFromDt = if (now || window.contains(nowDt)) nowDt else window.nextStart(nowDt)
        val saveFrom = saveFromDt.atZone(zone).toInstant().toEpochMilli()
        val stopAt = window.endFor(nowDt).atZone(zone).toInstant().toEpochMilli()

        Recorder.update {
            RecorderState(
                phase = if (System.currentTimeMillis() >= saveFrom) Phase.RECORDING else Phase.WAITING,
                saveFrom = saveFrom,
                stopAt = stopAt,
            )
        }

        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        try {
            ServiceCompat.startForeground(this, Notifications.RECORDING_ID, Notifications.recording(this, Recorder.state.value), type)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot start foreground", e)
            Notifications.problem(this, "Не получилось включить запись. Откройте Сонник и нажмите «Начать сейчас».")
            Recorder.reset()
            stopSelf()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Notifications.problem(this, "Нет доступа к микрофону. Откройте Сонник и разрешите его.")
            finishSession(0)
            return
        }

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sonnik:night")
            .apply { acquire(stopAt - System.currentTimeMillis() + 60_000) }

        val config = DetectorConfig(
            thresholdDb = prefs.threshold.toDouble(),
            minSpeechRatio = if (prefs.anySound) 0.0 else 0.45,
        )
        running = true
        worker = Thread({ listen(config, saveFrom, stopAt, saveFromDt) }, "sonnik-mic").also { it.start() }
    }

    @SuppressLint("MissingPermission") // checked in begin()
    private fun listen(config: DetectorConfig, saveFrom: Long, stopAt: Long, nightStart: LocalDateTime) {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            // VOICE_RECOGNITION: no automatic gain or noise suppression, so the room floor stays honest.
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(minBuf, RATE) * 2)
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord failed", e); null
        }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release()
            main.post {
                Notifications.problem(this, "Микрофон занят другим приложением. Запись не началась.")
                finishSession(0)
            }
            return
        }

        val detector = Detector(RATE, config)
        val zone = ZoneId.systemDefault()
        var nightDir: File? = null
        var clips = 0
        val buf = ShortArray(RATE / 10)
        var lastUi = 0L
        var lastPhase = Recorder.state.value.phase

        rec.startRecording()
        val streamStart = System.currentTimeMillis()

        fun keep(ep: Episode) {
            val at = streamStart + (ep.startS * 1000).toLong()
            if (at + (ep.durationS * 1000).toLong() < saveFrom) return // still before the night window
            val dir = nightDir ?: Nights.dirFor(this, nightStart).also { nightDir = it }
            runCatching {
                Nights.save(dir, LocalDateTime.ofInstant(Instant.ofEpochMilli(at), zone), ep)
                clips++
                Recorder.update { it.copy(clips = clips, lastClipAt = at) }
            }.onFailure { Log.w(TAG, "Save failed", it) }
        }

        try {
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) {
                    if (n < 0) Thread.sleep(100)
                    continue
                }
                detector.process(buf, n).forEach(::keep)

                val now = System.currentTimeMillis()
                if (now >= stopAt) break
                if (now - lastUi >= 500) {
                    lastUi = now
                    val phase = if (now >= saveFrom) Phase.RECORDING else Phase.WAITING
                    if (phase == Phase.RECORDING && nightDir == null) nightDir = Nights.dirFor(this, nightStart)
                    val floor = detector.floorDb
                    val level = if (floor == null) 0f
                    else ((detector.lastDb - floor) / (config.thresholdDb * 2)).toFloat().coerceIn(0f, 1f)
                    Recorder.update { it.copy(phase = phase, level = level, calibrated = floor != null) }
                    if (phase != lastPhase) {
                        lastPhase = phase
                        main.post { Notifications.refreshRecording(this) }
                    }
                }
            }
            detector.flush().forEach(::keep)
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        val total = clips
        main.post { finishSession(total) }
    }

    private fun finishSession(clips: Int) {
        running = false
        worker = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        Recorder.reset()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (clips > 0) Notifications.morning(this, clips)
        Scheduler.sync(this)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "sonnik.STOP"
        const val EXTRA_NOW = "now"
        private const val RATE = 16000
        private const val TAG = "Sonnik"
    }
}
