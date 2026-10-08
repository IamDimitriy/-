package sonnik.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.time.LocalDateTime
import java.time.ZoneId

/** Keeps exactly one alarm set for the start of the next night (one minute early, to calibrate). */
object Scheduler {

    private fun alarmIntent(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 1, Intent(ctx, AlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Start of the next night the app should record by itself, or null if auto-start is off. */
    fun nextStart(ctx: Context, now: LocalDateTime = LocalDateTime.now()): LocalDateTime? {
        val prefs = Prefs(ctx)
        if (!prefs.autoStart) return null
        // "+1 minute": the alarm fires a minute before the start; from then on it is "tonight".
        val start = prefs.window.nextStart(now.plusMinutes(1))
        return if (start == prefs.skippedStart) prefs.window.nextStart(start) else start
    }

    /** Skip tonight's automatic start (or undo that with [skip] = false). */
    fun skipTonight(ctx: Context, skip: Boolean) {
        val prefs = Prefs(ctx)
        prefs.skippedStart = if (skip) prefs.window.nextStart(LocalDateTime.now().plusMinutes(1)) else null
        sync(ctx)
    }

    fun isTonightSkipped(ctx: Context, now: LocalDateTime = LocalDateTime.now()): Boolean {
        val prefs = Prefs(ctx)
        return prefs.skippedStart != null && prefs.skippedStart == prefs.window.nextStart(now.plusMinutes(1))
    }

    fun canExact(ctx: Context): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    }

    fun sync(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(ctx)
        am.cancel(pi)
        val start = nextStart(ctx) ?: return
        val at = start.minusMinutes(1).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val prefs = Prefs(ctx)
        val skipped = prefs.skippedStart != null &&
            prefs.skippedStart == prefs.window.nextStart(LocalDateTime.now().minusMinutes(5))
        Scheduler.sync(ctx) // arm the next night
        Log.i("Sonnik", "Night alarm: skipped=$skipped auto=${prefs.autoStart} phase=${Recorder.state.value.phase}")
        if (skipped || !prefs.autoStart) return
        if (Recorder.state.value.phase != Phase.IDLE) return // already listening
        Notifications.startPrompt(ctx)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Notifications.createChannels(ctx)
        Scheduler.sync(ctx)
        // Rebooted in the middle of the night: offer to carry on (a reboot stops the microphone).
        val prefs = Prefs(ctx)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && prefs.autoStart &&
            prefs.window.contains(LocalDateTime.now()) && Recorder.state.value.phase == Phase.IDLE
        ) {
            Notifications.startPrompt(ctx)
        }
    }
}
