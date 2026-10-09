package sonnik.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object Notifications {
    const val RECORDING_ID = 1
    const val START_ID = 2
    const val ALARM_ID = 5
    private const val MORNING_ID = 3
    private const val PROBLEM_ID = 4

    private const val CH_RECORDING = "recording"
    private const val CH_START = "start"
    private const val CH_INFO = "info"
    private const val CH_ALARM = "alarm"
    /** The ringing alarm when [AlarmService] plays the sound itself: the notification stays silent. */
    const val CH_RINGING = "ringing"
    private const val TAG = "Sonnik"

    private val hhmm = DateTimeFormatter.ofPattern("HH:mm")
    fun time(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(hhmm)

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_RECORDING, "Идёт запись", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Постоянное уведомление, пока микрофон включён"
                setShowBadge(false)
            }
        )
        // High importance is required to open the app over the lock screen; sound and vibration
        // are off so nothing wakes you at midnight.
        nm.createNotificationChannel(
            NotificationChannel(CH_START, "Автозапуск ночью", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Включает запись в начале ночи"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALARM, "Будильник", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Звонок будильника утром"
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 400, 600, 400, 600)
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_RINGING, "Будильник звонит", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Экран будильника; мелодию играет сам Сонник на громкости будильника"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_INFO, "Итоги ночи", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
            }
        )
    }

    private fun openApp(ctx: Context, records: Boolean = false): PendingIntent =
        PendingIntent.getActivity(
            ctx, if (records) 11 else 10,
            Intent(ctx, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_RECORDS, records)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun recording(ctx: Context, s: RecorderState) = NotificationCompat.Builder(ctx, CH_RECORDING)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(
            if (s.phase == Phase.WAITING) "Запись начнётся в ${time(s.saveFrom)}"
            else "Слушаю до ${time(s.stopAt)}"
        )
        .setContentText(
            when {
                s.phase == Phase.WAITING -> "Микрофон включён, сохранять буду только ночные фразы"
                s.clips == 0 -> "Сохраняю только то, что вы скажете"
                else -> "Записано: ${phrases(s.clips)}, последняя в ${time(s.lastClipAt)}"
            }
        )
        .setOngoing(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(openApp(ctx))
        .addAction(
            0, "Остановить",
            PendingIntent.getService(
                ctx, 20, Intent(ctx, RecorderService::class.java).setAction(RecorderService.ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()

    fun refreshRecording(ctx: Context) {
        if (Recorder.state.value.phase == Phase.IDLE) return
        notify(ctx, RECORDING_ID, recording(ctx, Recorder.state.value))
    }

    /**
     * Midnight start: a full-screen notification opens [WakeActivity] over the lock screen, which
     * is the moment Android allows the app to turn the microphone on.
     */
    fun startPrompt(ctx: Context) {
        val wake = PendingIntent.getActivity(
            ctx, 30,
            Intent(ctx, WakeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CH_START)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Пора записывать сон")
            .setContentText("Нажмите, чтобы включить запись")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(wake, true)
            .setContentIntent(wake)
            .setAutoCancel(true)
            // No setSilent(): AndroidX puts "silent" notifications into a group that does not
            // alert, and Android then refuses the full-screen launch. The channel is already silent.
            .setTimeoutAfter(60 * 60 * 1000L)
            .build()
        Log.i(TAG, "Night start prompt posted")
        notify(ctx, START_ID, n)
    }

    /**
     * The ringing alarm: opens [AlarmActivity] over the lock screen, with "turn off" and
     * "10 more minutes" buttons. Normally silent, as [AlarmService] plays the melody; with
     * [sound] (the service could not start) the notification itself plays the alarm sound over
     * and over (FLAG_INSISTENT) for up to 10 minutes.
     */
    fun alarmNotification(ctx: Context, smart: Boolean, sound: Boolean): android.app.Notification {
        val screen = PendingIntent.getActivity(
            ctx, 40,
            Intent(ctx, AlarmActivity::class.java).putExtra(AlarmActivity.EXTRA_SMART, smart)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        fun action(name: String, code: Int) = PendingIntent.getBroadcast(
            ctx, code, Intent(ctx, AlarmActionReceiver::class.java).setAction(name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(ctx, if (sound) CH_ALARM else CH_RINGING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Доброе утро")
            .setContentText(if (smart) "Будильник: сейчас сон лёгкий, хорошее время проснуться" else "Будильник")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(screen, true)
            .setContentIntent(screen)
            .setOngoing(true)
            .addAction(0, "Выключить", action(AlarmActionReceiver.ACTION_DISMISS, 41))
            .addAction(0, "Ещё 10 минут", action(AlarmActionReceiver.ACTION_SNOOZE, 42))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (sound) b.setTimeoutAfter(10 * 60 * 1000L)
        // AlarmService posts the same notification again: that must not open the screen twice.
        else b.setOnlyAlertOnce(true)
        val n = b.build()
        if (sound) n.flags = n.flags or android.app.Notification.FLAG_INSISTENT
        return n
    }

    fun alarm(ctx: Context, smart: Boolean, sound: Boolean = false) =
        notify(ctx, ALARM_ID, alarmNotification(ctx, smart, sound))

    fun cancelStartPrompt(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(START_ID)

    fun morning(ctx: Context, clips: Int, sounds: Int = 0, snoreMinutes: Int = 0) {
        val snore = (if (sounds > 0) " · ${soundsText(sounds)}" else "") +
            (if (snoreMinutes > 0) " · храп ${minutesText(snoreMinutes)}" else "")
        val n = NotificationCompat.Builder(ctx, CH_INFO)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("За ночь: ${phrases(clips)}$snore")
            .setContentText("Нажмите, чтобы послушать")
            .setContentIntent(openApp(ctx, records = true))
            .addAction(
                0, "Записать сон",
                PendingIntent.getActivity(
                    ctx, 12,
                    Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_NEW_DREAM, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setAutoCancel(true)
            .build()
        notify(ctx, MORNING_ID, n)
    }

    fun problem(ctx: Context, text: String) {
        val n = NotificationCompat.Builder(ctx, CH_INFO)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Сонник не записывает")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()
        notify(ctx, PROBLEM_ID, n)
    }

    private fun notify(ctx: Context, id: Int, n: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            if (id == START_ID || id == ALARM_ID) EventLog.add(ctx, "Уведомления запрещены: Android не покажет «${n.extras.getCharSequence("android.title")}»")
            return
        }
        NotificationManagerCompat.from(ctx).notify(id, n)
    }
}

fun phrases(n: Int): String = "$n ${plural(n, "фраза", "фразы", "фраз")}"

fun soundsText(n: Int): String = "$n ${plural(n, "звук", "звука", "звуков")}"

/** "45 мин", "1 ч 20 мин". */
fun minutesText(m: Int): String = when {
    m < 60 -> "$m мин"
    m % 60 == 0 -> "${m / 60} ч"
    else -> "${m / 60} ч ${m % 60} мин"
}

fun plural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10
    val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> one
        m10 in 2..4 && m100 !in 12..14 -> few
        else -> many
    }
}

/** "4,2 МБ" style size for the recordings summary. */
fun megabytes(bytes: Long): String = "%.1f МБ".format(java.util.Locale("ru"), bytes / 1_048_576.0)
