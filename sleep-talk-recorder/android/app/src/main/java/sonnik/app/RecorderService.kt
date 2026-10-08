package sonnik.app

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import sonnik.core.Detector
import sonnik.core.DetectorConfig
import sonnik.core.ActivityMeter
import sonnik.core.Episode
import sonnik.core.Minute
import sonnik.core.NightPlan
import sonnik.core.NightSummary
import sonnik.core.SmartWake
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

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
        if (!running) begin(intent)
        return START_NOT_STICKY
    }

    private fun begin(intent: Intent?) {
        val prefs = Prefs(this)
        val zone = ZoneId.systemDefault()
        val nowDt = LocalDateTime.now()
        val basePlan = NightPlan.make(prefs.window, nowDt, intent?.getBooleanExtra(EXTRA_NOW, false) ?: false)
        // With the alarm on, keep listening until it rings (the smart alarm needs the sounds).
        val alarmAt = Alarm.next(this, nowDt)
        val plan = if (alarmAt != null && alarmAt.plusMinutes(30) > basePlan.stopAt) {
            basePlan.copy(stopAt = alarmAt.plusMinutes(30))
        } else basePlan
        val saveFrom = plan.saveFrom.atZone(zone).toInstant().toEpochMilli()
        val stopAt = plan.stopAt.atZone(zone).toInstant().toEpochMilli()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            bailOut("Нет доступа к микрофону. Откройте Сонник и разрешите его.")
            return
        }

        Recorder.update {
            RecorderState(
                phase = if (System.currentTimeMillis() >= saveFrom) Phase.RECORDING else Phase.WAITING,
                saveFrom = saveFrom,
                stopAt = stopAt,
                alarmAt = alarmAt?.let(Alarm::epochMs) ?: 0,
                alarmWindow = if (alarmAt != null) prefs.alarmWindow else 0,
            )
        }
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        try {
            ServiceCompat.startForeground(this, Notifications.RECORDING_ID, Notifications.recording(this, Recorder.state.value), type)
        } catch (e: Exception) {
            // Android refuses the microphone to a service started from the background.
            Log.w(TAG, "Cannot start foreground", e)
            Recorder.reset()
            bailOut("Не получилось включить запись. Откройте Сонник и нажмите «Начать сейчас».")
            return
        }

        Log.i(TAG, "Recording session: keep from ${plan.saveFrom}, stop at ${plan.stopAt}")
        val input = (inputFactory ?: ::defaultInput)(this, intent)
        if (!input.start()) {
            Log.w(TAG, "Audio input did not start: $input")
            Notifications.problem(this, "Микрофон занят другим приложением. Запись не началась.")
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
        val wake = alarmAt?.let { WakePlan(it, prefs.alarmWindow) }
        worker = Thread({ listen(input, config, plan, wake) }, "sonnik-mic").also { it.start() }
    }

    /**
     * A service started with startForegroundService must call startForeground, or Android
     * kills the app. When recording is impossible, satisfy that with a short service and stop.
     */
    private fun bailOut(message: String) {
        Notifications.problem(this, message)
        runCatching {
            val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE else 0
            ServiceCompat.startForeground(this, Notifications.RECORDING_ID, Notifications.recording(this, RecorderState()), type)
        }
        finishSession(0)
    }

    /** Wake-up time and how many minutes earlier the smart alarm may ring. */
    private data class WakePlan(val at: LocalDateTime, val windowMin: Int)

    private fun listen(input: AudioInput, config: DetectorConfig, plan: NightPlan, wake: WakePlan?) {
        val zone = ZoneId.systemDefault()
        val rate = input.sampleRate
        val detector = Detector(rate, config)
        val saveFrom = plan.saveFrom.atZone(zone).toInstant().toEpochMilli()
        val stopAt = plan.stopAt.atZone(zone).toInstant().toEpochMilli()
        var nightDir: File? = null
        var clips = 0
        val buf = ShortArray(rate / 10)
        var lastUi = 0L
        var lastPhase = Recorder.state.value.phase
        val streamStart = System.currentTimeMillis()
        var samples = 0L
        // Clock of the audio itself: equals the wall clock for the mic, runs faster for test input.
        fun audioNow() = maxOf(System.currentTimeMillis(), streamStart + samples * 1000 / rate)

        // Minute-by-minute statistics from the moment the night starts.
        val minutes = ArrayList<Minute>()
        val meter = ActivityMeter(thresholdDb = config.thresholdDb, startS = maxOf(0.0, (saveFrom - streamStart) / 1000.0))
        var rang = false
        fun minuteDone(m: Minute) {
            minutes += m
            val dir = nightDir ?: Nights.dirFor(this, plan.saveFrom).also { nightDir = it }
            runCatching { Nights.appendMinute(dir, m) }.onFailure { Log.w(TAG, "Activity write failed", it) }
            val snore = NightSummary.of(minutes).snoreMinutes
            Recorder.update { it.copy(snoreMinutes = snore) }
            if (wake != null && !rang) {
                val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(audioNow()), zone)
                if (SmartWake.shouldWake(now, wake.at, wake.windowMin, minutes)) {
                    rang = true
                    val smart = now.isBefore(wake.at)
                    main.post { Alarm.ring(this, wake.at, smart) }
                }
            }
        }
        detector.onFrame = { f -> meter.add(f)?.let(::minuteDone) }

        fun keep(ep: Episode) {
            val at = streamStart + (ep.startS * 1000).toLong()
            val start = LocalDateTime.ofInstant(Instant.ofEpochMilli(at), zone)
            val end = start.plusNanos((ep.durationS * 1e9).toLong())
            if (!plan.keeps(start, end)) return
            val dir = nightDir ?: Nights.dirFor(this, plan.saveFrom).also { nightDir = it }
            runCatching {
                Nights.save(dir, start, ep)
                clips++
                Recorder.update { it.copy(clips = clips, lastClipAt = at) }
                main.post { Notifications.refreshRecording(this) }
            }.onFailure { Log.w(TAG, "Save failed", it) }
        }

        try {
            while (running) {
                val n = input.read(buf)
                if (n < 0) break // file input ended
                if (n > 0) {
                    samples += n
                    detector.process(buf, n).forEach(::keep)
                }

                val now = audioNow()
                if (now >= stopAt) break
                if (now - lastUi >= 500) {
                    lastUi = now
                    val phase = if (now >= saveFrom) Phase.RECORDING else Phase.WAITING
                    if (phase == Phase.RECORDING && nightDir == null) nightDir = Nights.dirFor(this, plan.saveFrom)
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
            meter.flush()?.let(::minuteDone)
        } catch (e: Exception) {
            Log.w(TAG, "Recording failed", e)
        } finally {
            input.close()
        }
        val total = clips
        val snore = NightSummary.of(minutes).snoreMinutes
        Log.i(TAG, "Recording session ended, clips kept: $total, snoring: $snore min, minutes: ${minutes.size}")
        main.post { finishSession(total, snore) }
    }

    private fun finishSession(clips: Int, snoreMinutes: Int = 0) {
        running = false
        worker = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        Recorder.reset()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (clips > 0 || snoreMinutes > 0) Notifications.morning(this, clips, snoreMinutes)
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
        /** Debug builds only: name of a WAV file in the app's files dir to play instead of the mic. */
        const val EXTRA_DEMO_WAV = "demo_wav"
        private const val TAG = "Sonnik"

        /** Lets tests feed audio without a microphone. */
        @VisibleForTesting
        @Volatile
        var inputFactory: ((Context, Intent?) -> AudioInput)? = null

        private fun defaultInput(ctx: Context, intent: Intent?): AudioInput {
            val demo = intent?.getStringExtra(EXTRA_DEMO_WAV)
            if (BuildConfig.DEBUG && demo != null) return WavFileInput(File(ctx.filesDir, demo), realTime = true)
            return MicInput()
        }
    }
}
