package sonnik.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Plays the ringing alarm: the phone's alarm melody at alarm volume, quiet at first and louder
 * over half a minute, with vibration, until it is turned off or snoozed, or for 10 minutes.
 *
 * A foreground service plays it, as clock apps do, so the alarm sounds even when the
 * notification sound would not (notification sounds muted, notifications switched off).
 */
class AlarmService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var volume = START_VOLUME

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val smart = intent?.getBooleanExtra(AlarmActivity.EXTRA_SMART, false) ?: false
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        try {
            ServiceCompat.startForeground(this, Notifications.ALARM_ID, Notifications.alarmNotification(this, smart, sound = false), type)
        } catch (e: Exception) {
            Log.w(TAG, "Alarm service cannot run in the foreground", e)
            EventLog.add(this, "Будильник: Android не дал включить звук будильника, звоню звуком уведомления")
            Notifications.alarm(this, smart, sound = true)
            stopSelf()
            return START_NOT_STICKY
        }
        _ringing.value = true
        play()
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, RING_MS)
        return START_NOT_STICKY
    }

    /** Nobody reacted for 10 minutes: stop; [Alarm] rings again later if it should. */
    private val timeout = Runnable {
        EventLog.add(this, "Будильник: никто не выключил за 10 минут")
        stopSelf()
    }

    private val louder = object : Runnable {
        override fun run() {
            volume = (volume + 0.08f).coerceAtMost(1f)
            player?.setVolume(volume, volume)
            if (volume < 1f) handler.postDelayed(this, 2_000)
        }
    }

    private fun play() {
        if (player != null || ringtone != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val p = MediaPlayer()
        val ok = uri != null && runCatching {
            p.setAudioAttributes(attrs)
            p.setDataSource(this, uri)
            p.isLooping = true
            p.setVolume(volume, volume)
            p.prepare()
            p.start()
        }.isSuccess
        if (ok) {
            player = p
            handler.postDelayed(louder, 2_000)
        } else {
            p.release()
            // The melody cannot be opened (deleted, on a removed card): the system's own player.
            ringtone = runCatching {
                RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))?.also { r ->
                    r.audioAttributes = attrs
                    if (Build.VERSION.SDK_INT >= 28) r.isLooping = true
                    r.play()
                }
            }.getOrNull()
            Log.w(TAG, "Alarm melody unavailable ($uri), ringtone fallback: ${ringtone != null}")
        }
        vibrator = ContextCompat.getSystemService(this, Vibrator::class.java)?.also { v ->
            runCatching {
                @Suppress("DEPRECATION")
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0), attrs)
            }
        }
    }

    override fun onDestroy() {
        _ringing.value = false
        handler.removeCallbacksAndMessages(null)
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        ringtone?.let { runCatching { it.stop() } }
        ringtone = null
        vibrator?.cancel()
        vibrator = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Sonnik"
        private const val RING_MS = 10 * 60 * 1000L
        private const val START_VOLUME = 0.25f

        private val _ringing = MutableStateFlow(false)
        /** The alarm is sounding now: the app shows its own "turn off" button too. */
        val ringing: StateFlow<Boolean> = _ringing

        /** Starts ringing; false when Android refused (the caller then rings with a notification). */
        fun start(ctx: Context, smart: Boolean): Boolean = runCatching {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, AlarmService::class.java).putExtra(AlarmActivity.EXTRA_SMART, smart),
            )
        }.onFailure { Log.w(TAG, "Alarm service refused", it) }.isSuccess

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, AlarmService::class.java))
        }
    }
}
