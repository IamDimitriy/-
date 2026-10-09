package sonnik.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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

    /** An exact alarm the system shows as the next alarm (it may wake the phone and open screens). */
    fun setAlarmClock(ctx: Context, atMs: Long, pi: PendingIntent) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        if (canExact(ctx)) {
            val show = PendingIntent.getActivity(
                ctx, 4, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(atMs, show), pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        }
    }

    fun sync(ctx: Context) {
        Alarm.syncBackup(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(ctx)
        am.cancel(pi)
        val start = nextStart(ctx)
        val prefs = Prefs(ctx)
        if (start != prefs.armedFor) {
            prefs.armedFor = start
            EventLog.add(ctx, if (start == null) "Автозапуск выключен" else "Автозапуск поставлен на ${start.format(armedFmt)}")
        }
        if (start == null) return
        val at = start.minusMinutes(1).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    private val armedFmt = DateTimeFormatter.ofPattern("dd.MM HH:mm")

    /** How often the night start is tried again while it has not happened. */
    const val RETRY_MIN = 10L

    private fun retryIntent(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 7, Intent(ctx, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_RETRY),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Another try at tonight's start in [RETRY_MIN] minutes, or after [delayMs] (see [AlarmReceiver]). */
    fun armRetry(ctx: Context, delayMs: Long = RETRY_MIN * 60_000) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val at = System.currentTimeMillis() + delayMs
        if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, retryIntent(ctx))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, retryIntent(ctx))
    }

    fun cancelRetry(ctx: Context) = ctx.getSystemService(AlarmManager::class.java).cancel(retryIntent(ctx))

    /**
     * Whether tonight's automatic start is still to happen at [now]: inside the night, not
     * skipped, nothing recording, and either no session tonight yet or one that was cut short
     * (the phone restarted, Android closed the app). A session the user stopped counts as done.
     */
    fun startPending(ctx: Context, now: LocalDateTime = LocalDateTime.now()): Boolean {
        val prefs = Prefs(ctx)
        val w = prefs.window
        if (!prefs.autoStart || !w.contains(now) || Recorder.state.value.phase != Phase.IDLE) return false
        val tonight = w.currentStart(now)
        if (prefs.skippedStart == tonight) return false
        if (prefs.sessionOpen) return true
        return prefs.lastSessionAt?.isBefore(tonight.minusMinutes(5)) ?: true
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == ACTION_RETRY) {
            retry(ctx)
            return
        }
        val prefs = Prefs(ctx)
        val skipped = prefs.skippedStart != null &&
            prefs.skippedStart == prefs.window.nextStart(LocalDateTime.now().minusMinutes(5))
        Scheduler.sync(ctx) // arm the next night
        Log.i("Sonnik", "Night alarm: skipped=$skipped auto=${prefs.autoStart} phase=${Recorder.state.value.phase}")
        when {
            !prefs.autoStart -> return
            skipped -> EventLog.add(ctx, "Ночь: эту ночь пропускаю, как просили")
            Recorder.state.value.phase != Phase.IDLE -> {
                EventLog.add(ctx, "Ночь: запись уже идёт")
                Scheduler.armRetry(ctx) // keeps an eye on it through the night
            }
            else -> {
                EventLog.add(
                    ctx,
                    (if (screenOn(ctx)) "Ночь: телефоном пользуются, показываю уведомление «Пора записывать сон»"
                    else "Ночь: экран выключен, открываю запись") + Setup.problems(ctx),
                )
                Notifications.startPrompt(ctx)
                // If it does not start now (the phone is in use, say), try again until it does.
                Scheduler.armRetry(ctx)
            }
        }
    }

    /**
     * While the phone is in use Android only shows the start notification and does not open the
     * app over what the user is doing; once the screen is off, the next try can.
     */
    private fun retry(ctx: Context) {
        val prefs = Prefs(ctx)
        val now = LocalDateTime.now()
        // The night is over or auto-start is off: stop watching.
        if (!prefs.autoStart || !prefs.window.contains(now)) return
        // Recording: keep checking on it until morning, in case Android closes the app.
        if (Recorder.state.value.phase != Phase.IDLE) {
            Scheduler.armRetry(ctx)
            return
        }
        if (!Scheduler.startPending(ctx, now)) return
        if (prefs.sessionOpen) EventLog.add(ctx, "Запись оборвалась (Android закрыл приложение), включаю снова")
        if (screenOn(ctx)) {
            // A pop-up every ten minutes would only annoy; the notification stays in the shade.
            EventLog.add(ctx, "Повтор: телефоном ещё пользуются, жду, пока погаснет экран")
        } else {
            EventLog.add(ctx, "Повтор: экран погас, открываю запись" + Setup.problems(ctx))
            Notifications.cancelStartPrompt(ctx)
            Notifications.startPrompt(ctx)
        }
        Scheduler.armRetry(ctx)
    }

    private fun screenOn(ctx: Context) = ctx.getSystemService(PowerManager::class.java).isInteractive

    companion object {
        const val ACTION_RETRY = "sonnik.START_RETRY"
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Notifications.createChannels(ctx)
        Scheduler.sync(ctx)
        // Rebooted or updated in the middle of the night: carry on (either stops the microphone).
        val restart = intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (restart && Scheduler.startPending(ctx)) {
            EventLog.add(
                ctx,
                (if (intent.action == Intent.ACTION_BOOT_COMPLETED) "Телефон перезагрузился ночью" else "Сонник обновился ночью") +
                    ", включаю запись" + Setup.problems(ctx),
            )
            Notifications.startPrompt(ctx)
            Scheduler.armRetry(ctx)
        }
    }
}
