package sonnik.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.LocalDateTime
import java.time.ZoneId

/** Keeps exactly one alarm set for the start of the next night (one minute early, to calibrate). */
object Scheduler {

    private fun alarmIntent(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 1, Intent(ctx, AlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** When the next night's recording will begin, or null if auto-start is off. */
    fun nextStart(ctx: Context): LocalDateTime? {
        val prefs = Prefs(ctx)
        if (!prefs.autoStart) return null
        return prefs.window.nextStart(LocalDateTime.now().plusMinutes(1))
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
        Scheduler.sync(ctx) // arm tomorrow
        if (Recorder.state.value.phase != Phase.IDLE) return // already listening ("Ложусь спать")
        if (!Prefs(ctx).autoStart) return
        Notifications.startPrompt(ctx)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Notifications.createChannels(ctx)
        Scheduler.sync(ctx)
    }
}
