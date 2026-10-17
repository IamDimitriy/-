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
import sonnik.core.ClipPolicy
import sonnik.core.SoundKind
import sonnik.core.Episode
import sonnik.core.Minute
import sonnik.core.NightPlan
import sonnik.core.NightSummary
import sonnik.core.SleepOnset
import sonnik.core.SmartWake
import sonnik.core.VoiceTrim
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service that owns the microphone for the night.
 *
 * It must be started while the app is visible (from the app or from [WakeActivity]): Android
 * only lets a foreground service use the microphone if it was started from the foreground.
 */
class RecorderService : Service() {

    @Volatile private var running = false
    /** When the sleepers fall asleep; speech before that is talking, not sleep talk. */
    @Volatile private var gate: SleepOnset? = null
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
        if (intent?.action == ACTION_ASLEEP) {
            // "We are asleep": the recording loop notices it within half a second.
            gate?.sleepNow(LocalDateTime.now())
            if (worker == null) stopSelf() // nothing is being recorded: nothing to keep running for
            return START_NOT_STICKY
        }
        if (!running) begin(intent)
        return START_NOT_STICKY
    }

    private fun begin(intent: Intent?) {
        val prefs = Prefs(this)
        val zone = ZoneId.systemDefault()
        val nowDt = LocalDateTime.now()
        val startNow = intent?.getBooleanExtra(EXTRA_NOW, false) ?: false
        val (stopDt, wake) = scheduleFor(prefs, nowDt, nowDt)
        val plan = NightPlan(NightPlan.make(prefs.window, nowDt, startNow).saveFrom, stopDt)
        val saveFrom = plan.saveFrom.atZone(zone).toInstant().toEpochMilli()
        val stopAt = plan.stopAt.atZone(zone).toInstant().toEpochMilli()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            EventLog.add(this, "Запись не началась: нет доступа к микрофону")
            bailOut("Нет доступа к микрофону. Откройте Сонник и разрешите его.")
            return
        }

        val onset = prefs.onsetMin.coerceAtLeast(0)
        gate = SleepOnset(Duration.ofMinutes(onset.toLong()), plan.saveFrom)
        Recorder.update {
            RecorderState(
                phase = if (System.currentTimeMillis() >= saveFrom) Phase.RECORDING else Phase.WAITING,
                saveFrom = saveFrom,
                stopAt = stopAt,
                alarmAt = wake?.at?.let(Alarm::epochMs) ?: 0,
                alarmWindow = wake?.windowMin ?: 0,
                onsetMin = onset,
            )
        }
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        try {
            ServiceCompat.startForeground(this, Notifications.RECORDING_ID, Notifications.recording(this, Recorder.state.value), type)
        } catch (e: Exception) {
            // Android refuses the microphone to a service started from the background.
            Log.w(TAG, "Cannot start foreground", e)
            EventLog.add(this, "Запись не началась: Android не дал включить микрофон в фоне")
            Recorder.reset()
            bailOut("Не получилось включить запись. Откройте Сонник и нажмите «Начать сейчас».")
            return
        }
        SleepTile.update(this)

        Log.i(TAG, "Recording session: keep from ${plan.saveFrom}, stop at ${plan.stopAt}")
        val input = (inputFactory ?: ::defaultInput)(this, intent)
        if (!input.start()) {
            Log.w(TAG, "Audio input did not start: $input")
            EventLog.add(this, "Запись не началась: микрофон занят другим приложением")
            Notifications.problem(this, "Микрофон занят другим приложением. Запись не началась.")
            finishSession(0)
            return
        }
        // Tonight is taken care of: no more start reminders. The retry alarm now watches that the
        // session is still alive and starts it again if Android closes the app in the night.
        prefs.lastSessionAt = nowDt
        prefs.sessionOpen = true
        Scheduler.armRetry(this)
        Notifications.cancelStartPrompt(this)
        EventLog.add(
            this,
            "Запись началась" + (if (startNow) " (вручную)" else "") +
                ": сохраняю с ${Notifications.time(saveFrom)} до ${Notifications.time(stopAt)}" +
                (wake?.let { ", будильник в ${Notifications.time(Alarm.epochMs(it.at))}" } ?: ""),
        )

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sonnik:night")
            // Not counted: acquiring again (the session got longer) only moves the timeout.
            .apply { setReferenceCounted(false); acquire(stopAt - System.currentTimeMillis() + 60_000) }

        // The most sensitive setting: any sound 4 dB above the room's silence becomes a clip,
        // and the classifier decides whether it is speech, snoring, a creak or the street.
        val config = DetectorConfig(thresholdDb = SENSITIVITY_DB, minSpeechRatio = 0.0)
        running = true
        worker = Thread({ listen(input, config, plan, wake, nowDt) }, "sonnik-mic").also { it.start() }
    }

    /**
     * When a session that began at [startedAt] stops and which wake-up it watches for, from the
     * settings as they are [now]: the wake-up time, its window or the end of the night may be
     * changed while the session runs.
     */
    private fun scheduleFor(prefs: Prefs, startedAt: LocalDateTime, now: LocalDateTime): Pair<LocalDateTime, WakePlan?> {
        var stop = prefs.window.endFor(startedAt)
        // The first wake-up after the session began (within a night's reach: an alarm at 23:50
        // seen from midnight is tomorrow's). One whose time has passed without ringing is left
        // to the backup alarm; this session does not wait for it.
        val alarmAt = if (prefs.alarmOn) {
            SmartWake.nextAlarm(startedAt, prefs.alarmTime).takeIf { it.isBefore(startedAt.plusHours(16)) }
        } else null
        val tonight = alarmAt?.takeIf { it == prefs.rangFor || !it.isBefore(now.minusMinutes(1)) }
        // With the alarm on, keep listening until it rings (the smart alarm needs the sounds).
        if (tonight != null && tonight.plusMinutes(30) > stop) stop = tonight.plusMinutes(30)
        // One that already rang (and was snoozed) is not watched again.
        val wake = tonight?.takeIf { it != prefs.rangFor }?.let { WakePlan(it, prefs.alarmWindow) }
        return stop to wake
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

    private fun listen(input: AudioInput, config: DetectorConfig, firstPlan: NightPlan, firstWake: WakePlan?, startedAt: LocalDateTime) {
        val zone = ZoneId.systemDefault()
        val rate = input.sampleRate
        val detector = Detector(rate, config)
        // The end and the wake-up follow the settings while the session runs (see scheduleFor).
        var plan = firstPlan
        var wake = firstWake
        val saveFrom = plan.saveFrom.atZone(zone).toInstant().toEpochMilli()
        var stopAt = plan.stopAt.atZone(zone).toInstant().toEpochMilli()
        var lastSettings = 0L
        var nightDir: File? = null
        val phrases = AtomicInteger()
        val sounds = AtomicInteger()
        val unsure = AtomicInteger()
        val takeSamples = Prefs(this).soundSamples
        /** Starts (ms) of the clips waiting to be heard, oldest first: the gate waits for them. */
        val unheard = java.util.concurrent.ConcurrentLinkedQueue<Long>()
        var asleepNoted = false
        /** What the notification last showed: minutes of quiet while awake, -1 once asleep. */
        var lastStatus = -1
        // Classifying a long clip takes a moment, so it happens off the thread that reads the mic.
        val saver = Executors.newSingleThreadExecutor()
        val classifier = (classifierFactory ?: YamnetClassifier::createOrFallback)(this)
        val policy = ClipPolicy()
        val buf = ShortArray(rate / 10)
        var lastUi = 0L
        var lastPhase = Recorder.state.value.phase
        var streamStart = System.currentTimeMillis()
        var samples = 0L
        // Clock of the audio itself: equals the wall clock for the mic, runs faster for test input.
        fun audioNow() = maxOf(System.currentTimeMillis(), streamStart + samples * 1000 / rate)

        // Minute-by-minute statistics from the moment the night starts.
        val minutes = ArrayList<Minute>()
        val meter = ActivityMeter(startS = maxOf(0.0, (saveFrom - streamStart) / 1000.0))
        /** The wake-up time this session already rang for. */
        var rangAt: LocalDateTime? = null
        fun minuteDone(m: Minute) {
            minutes += m
            val dir = nightDir ?: Nights.dirFor(this, plan.saveFrom).also { nightDir = it }
            runCatching { Nights.appendMinute(dir, m) }.onFailure { Log.w(TAG, "Activity write failed", it) }
            val snore = NightSummary.of(minutes).snoreMinutes
            Recorder.update { it.copy(snoreMinutes = snore) }
            val w = wake
            if (w != null && rangAt != w.at) {
                val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(audioNow()), zone)
                if (SmartWake.shouldWake(now, w.at, w.windowMin, minutes)) {
                    rangAt = w.at
                    val smart = now.isBefore(w.at)
                    main.post { Alarm.ring(this, w.at, smart) }
                }
            }
        }
        detector.onFrame = { f -> meter.add(f)?.let(::minuteDone) }

        fun keep(ep: Episode) {
            val at = streamStart + (ep.startS * 1000).toLong()
            val start = LocalDateTime.ofInstant(Instant.ofEpochMilli(at), zone)
            val end = start.plusNanos((ep.durationS * 1e9).toLong())
            if (!plan.keeps(start, end)) return
            // Awake and telling a dream to the phone: not sleep talk.
            if (Recorder.heardAwake(at, at + (ep.durationS * 1000).toLong())) return
            val dir = nightDir ?: Nights.dirFor(this, plan.saveFrom).also { nightDir = it }
            unheard.add(at)
            saver.execute {
                try { runCatching {
                    val g = gate
                    // 1. The voice: the noise around a phrase is cut off, phrases far apart are
                    // separate pieces, and each piece is judged on its own (a long clip of hiss
                    // gave the network too many chances to hear "speech" somewhere).
                    var phrase = false
                    for (piece in VoiceTrim.pieces(ep.audio, ep.sampleRate)) {
                        val heard = classifier.listen(piece.audio, ep.sampleRate)
                        val voice = heard.voiceClass ?: continue
                        if (voice.kind == SoundKind.SPEECH) phrase = true
                        val pieceAt = start.plusNanos((piece.fromS * 1e9).toLong())
                        if (g != null && !g.asleep(pieceAt)) {
                            // Talking before sleep: it tells when they fall asleep, but is not kept.
                            if (voice.kind == SoundKind.SPEECH) g.heardSpeech(pieceAt)
                            Nights.note(dir, pieceAt, "до сна", heard)
                            continue
                        }
                        val clip = Episode(ep.startS + piece.fromS, ep.sampleRate, piece.audio, ep.peakDb, ep.activeS)
                        val f = Nights.save(dir, pieceAt, clip, voice)
                        Nights.note(dir, pieceAt, f.name, heard)
                        val pieceMs = at + (piece.fromS * 1000).toLong()
                        if (voice.kind == SoundKind.SPEECH) {
                            val n = phrases.incrementAndGet()
                            Recorder.update { it.copy(clips = n, lastClipAt = pieceMs) }
                        } else {
                            val n = unsure.incrementAndGet()
                            Recorder.update { it.copy(unsure = n) }
                        }
                    }
                    // 2. No phrase: a sample of snoring or a knock, never silence or steady noise
                    // (a bit of snoring that might be a voice does not hide the snoring itself).
                    if (!phrase) {
                        val heard = classifier.listen(ep.audio, ep.sampleRate)
                        val sound = heard.sound
                        if (sound == null) {
                            Nights.note(dir, start, Nights.NOISE, heard)
                            return@runCatching
                        }
                        // Snoring: asleep, whatever was said before.
                        if (heard.snoring) g?.heardSnoring(start)
                        val outcome = when {
                            g != null && !g.asleep(start) -> "до сна"
                            !takeSamples -> "образцы выключены"
                            !policy.keep(sound.kind, start) -> "хватит образцов"
                            else -> null
                        }
                        if (outcome != null) {
                            Nights.note(dir, start, outcome, heard)
                            return@runCatching
                        }
                        val f = Nights.save(dir, start, ep, sound)
                        Nights.note(dir, start, f.name, heard)
                        val n = sounds.incrementAndGet()
                        Recorder.update { it.copy(sounds = n) }
                    }
                    main.post { Notifications.refreshRecording(this) }
                }.onFailure { Log.w(TAG, "Save failed", it) } } finally { unheard.poll() }
            }
        }

        try {
            while (running) {
                val n = input.read(buf)
                if (n < 0) break // file input ended
                if (n > 0) {
                    samples += n
                    detector.process(buf, n).forEach(::keep)
                } else {
                    // The mic gave nothing (lost, or taken by a call): the sound's clock stood still
                    // while time went on. Catch up, or every later clip would be dated too early.
                    val lag = System.currentTimeMillis() - (streamStart + samples * 1000 / rate)
                    if (lag > CLOCK_SLIP_MS) streamStart += lag
                }

                val now = audioNow()
                if (now - lastSettings >= SETTINGS_EVERY_MS) {
                    lastSettings = now
                    val (stop, w) = scheduleFor(Prefs(this), startedAt, LocalDateTime.now())
                    if (stop != plan.stopAt || w != wake) {
                        plan = plan.copy(stopAt = stop)
                        wake = w
                        stopAt = stop.atZone(zone).toInstant().toEpochMilli()
                        Log.i(TAG, "Settings changed: stop at $stop, wake $w")
                        Recorder.update {
                            it.copy(stopAt = stopAt, alarmAt = w?.at?.let(Alarm::epochMs) ?: 0, alarmWindow = w?.windowMin ?: 0)
                        }
                        val until = stopAt
                        main.post {
                            wakeLock?.acquire(until - System.currentTimeMillis() + 60_000)
                            Notifications.refreshRecording(this)
                        }
                    }
                }
                if (now >= stopAt) break
                if (now - lastUi >= 500) {
                    lastUi = now
                    val phase = if (now >= saveFrom) Phase.RECORDING else Phase.WAITING
                    if (phase == Phase.RECORDING && nightDir == null) nightDir = Nights.dirFor(this, plan.saveFrom)
                    val floor = detector.floorDb
                    val level = if (floor == null) 0f
                    else ((detector.lastDb - floor) / (config.thresholdDb * 2)).toFloat().coerceIn(0f, 1f)
                    // Asleep after the quiet only as far as every sound before it has been heard:
                    // a phrase still being recorded or classified may be talk that ends the quiet.
                    val heardTo = minOf(
                        now,
                        detector.episodeStartS?.let { streamStart + (it * 1000).toLong() } ?: now,
                        unheard.peek() ?: now,
                    )
                    val nowDt = LocalDateTime.ofInstant(Instant.ofEpochMilli(heardTo), zone)
                    val g = gate
                    val since = if (phase == Phase.RECORDING) g?.asleepSince(nowDt) else null
                    val quiet = if (since == null && g != null) g.quietMinutes(nowDt) else 0
                    if (since != null && !asleepNoted) {
                        asleepNoted = true
                        // With speech kept from the start there is no moment to note.
                        if (Recorder.state.value.onsetMin > 0) {
                            val dir = nightDir ?: Nights.dirFor(this, plan.saveFrom).also { nightDir = it }
                            runCatching { Nights.setAsleep(dir, since) }
                            EventLog.add(this, "Уснули ≈ ${since.toLocalTime().withSecond(0).withNano(0)} (${g?.reason?.text ?: ""})")
                            main.post { SleepTile.update(this) }
                        }
                    }
                    val asleepMs = since?.atZone(zone)?.toInstant()?.toEpochMilli() ?: 0L
                    Recorder.update {
                        it.copy(phase = phase, level = level, calibrated = floor != null, asleepAt = asleepMs, quietMin = quiet)
                    }
                    val status = if (since != null || g == null) -1 else quiet
                    if (phase != lastPhase || status != lastStatus) {
                        lastPhase = phase
                        lastStatus = status
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
            saver.shutdown()
            saver.awaitTermination(2, TimeUnit.MINUTES)
            classifier.close()
        }
        val total = phrases.get()
        val other = sounds.get()
        val snore = NightSummary.of(minutes).snoreMinutes
        Log.i(TAG, "Recording session ended: $total phrases, $other other sounds, snoring $snore min, ${minutes.size} min")
        main.post { finishSession(total, other, snore) }
    }

    private fun finishSession(clips: Int, sounds: Int = 0, snoreMinutes: Int = 0) {
        val asleepAt = Recorder.state.value.let { if (it.onsetMin > 0) it.asleepAt else 0L }
        running = false
        worker = null
        gate = null
        Prefs(this).sessionOpen = false
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        Recorder.reset()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (clips > 0 || sounds > 0 || snoreMinutes > 0) Notifications.morning(this, clips, sounds, snoreMinutes, asleepAt)
        Scheduler.sync(this)
        SleepTile.update(this)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "sonnik.STOP"
        /** "We are asleep": keep speech from now on. */
        const val ACTION_ASLEEP = "sonnik.ASLEEP"
        const val EXTRA_NOW = "now"
        /** Debug builds only: name of a WAV file in the app's files dir to play instead of the mic. */
        const val EXTRA_DEMO_WAV = "demo_wav"
        private const val TAG = "Sonnik"
        private const val SENSITIVITY_DB = 4.0
        /** How often a running session re-reads the wake-up time and the end of the night. */
        private const val SETTINGS_EVERY_MS = 5_000L
        /** The sound's clock may fall this far behind the wall clock before it is moved on. */
        private const val CLOCK_SLIP_MS = 1_000L

        /** Lets tests use the rule-based classifier instead of the neural one. */
        @VisibleForTesting
        @Volatile
        var classifierFactory: ((Context) -> SoundClassifier)? = null

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
