package sonnik.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import sonnik.core.SmartWake
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The wake-up alarm.
 *
 * While the night is being recorded, [RecorderService] rings it early when sleep sounds light
 * (see [SmartWake]). A plain exact alarm at the wake-up time is always set as a backup, so the
 * alarm rings even if nothing was recorded. Whichever comes first rings; the other is ignored.
 *
 * The ringing stops by itself after 10 minutes, so if nobody reacts it rings again 10 minutes
 * later, up to [MAX_RINGS] times in a row.
 */
object Alarm {
    private const val TAG = "Sonnik"
    private const val SNOOZE_MIN = 10L
    private const val MAX_RINGS = 3

    /** The next wake-up time that has not rung yet, or null if the alarm is off. */
    fun next(ctx: Context, now: LocalDateTime = LocalDateTime.now()): LocalDateTime? {
        val prefs = Prefs(ctx)
        if (!prefs.alarmOn) return null
        val at = SmartWake.nextAlarm(now, prefs.alarmTime)
        return if (at == prefs.rangFor) SmartWake.nextAlarm(at, prefs.alarmTime) else at
    }

    /** Rings for the wake-up time [alarmFor], once. */
    fun ring(ctx: Context, alarmFor: LocalDateTime, smart: Boolean) {
        val prefs = Prefs(ctx)
        if (prefs.rangFor == alarmFor) return
        prefs.rangFor = alarmFor
        prefs.alarmRings = 0 // a new morning
        Log.i(TAG, "Alarm rings for $alarmFor (smart=$smart)")
        ringNow(ctx, smart)
        Scheduler.sync(ctx) // the backup moves to tomorrow
    }

    /** Rings now and, unless it is the last ring in a row, sets the repeat in 10 minutes. */
    internal fun ringNow(ctx: Context, smart: Boolean) {
        val prefs = Prefs(ctx)
        val rings = prefs.alarmRings + 1
        prefs.alarmRings = rings
        // The screen and its buttons come from the notification, the melody from AlarmService.
        Notifications.alarm(ctx, smart)
        if (!AlarmService.start(ctx, smart)) Notifications.alarm(ctx, smart, sound = true)
        EventLog.add(ctx, "Будильник звонит" + (if (smart) " (сон лёгкий)" else "") + (if (rings > 1) ", повтор $rings" else ""))
        if (rings < MAX_RINGS) {
            // The same alarm as a snooze: snoozing replaces it, turning off cancels it.
            Scheduler.setAlarmClock(ctx, System.currentTimeMillis() + SNOOZE_MIN * 60_000, snoozeIntent(ctx))
        }
        Log.i(TAG, "Alarm ring $rings of $MAX_RINGS")
    }

    fun dismiss(ctx: Context) {
        AlarmService.stop(ctx)
        NotificationManagerCompat.from(ctx).cancel(Notifications.ALARM_ID)
        EventLog.add(ctx, "Будильник выключен")
        ctx.getSystemService(AlarmManager::class.java).cancel(snoozeIntent(ctx)) // no repeat
        Prefs(ctx).alarmRings = 0
        if (Recorder.state.value.phase != Phase.IDLE) Recorder.stop(ctx) // you are awake now
    }

    fun snooze(ctx: Context) {
        AlarmService.stop(ctx)
        NotificationManagerCompat.from(ctx).cancel(Notifications.ALARM_ID)
        // Someone pressed it: the snoozed ring may repeat again if they fall back asleep.
        Prefs(ctx).alarmRings = 0
        val at = System.currentTimeMillis() + SNOOZE_MIN * 60_000
        Scheduler.setAlarmClock(ctx, at, snoozeIntent(ctx)) // replaces the pending repeat
        Log.i(TAG, "Alarm snoozed for $SNOOZE_MIN min")
    }

    internal fun backupIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 2, Intent(ctx, AlarmRingReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** The end of a snooze and the repeat of an unanswered ring: one alarm, set or cancelled together. */
    internal fun snoozeIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 3, Intent(ctx, AlarmRingReceiver::class.java).putExtra(AlarmRingReceiver.EXTRA_SNOOZE, true),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun epochMs(t: LocalDateTime) = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Keeps the backup alarm in line with the settings. */
    internal fun syncBackup(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = backupIntent(ctx)
        am.cancel(pi)
        // Switched off in the settings: a repeat or snooze still pending must not ring either.
        if (!Prefs(ctx).alarmOn) am.cancel(snoozeIntent(ctx))
        val at = next(ctx) ?: return
        Scheduler.setAlarmClock(ctx, epochMs(at), pi)
    }
}

/** Backup alarm at the wake-up time, and the end of a snooze or the repeat of an unanswered ring. */
class AlarmRingReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_SNOOZE, false)) {
            Alarm.ringNow(ctx, smart = false)
            return
        }
        val prefs = Prefs(ctx)
        if (!prefs.alarmOn) return
        // The wake-up time this backup was set for: the latest one at or before now.
        val due = SmartWake.nextAlarm(LocalDateTime.now().minusMinutes(5), prefs.alarmTime)
        Alarm.ring(ctx, due, smart = false)
    }

    companion object {
        const val EXTRA_SNOOZE = "snooze"
    }
}

/** "Turn off" and "10 more minutes" buttons on the alarm notification. */
class AlarmActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DISMISS -> Alarm.dismiss(ctx)
            ACTION_SNOOZE -> Alarm.snooze(ctx)
        }
    }

    companion object {
        const val ACTION_DISMISS = "sonnik.ALARM_DISMISS"
        const val ACTION_SNOOZE = "sonnik.ALARM_SNOOZE"
    }
}
