package sonnik.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object Notifications {
    const val RECORDING_ID = 1
    const val START_ID = 2
    private const val MORNING_ID = 3
    private const val PROBLEM_ID = 4

    private const val CH_RECORDING = "recording"
    private const val CH_START = "start"
    private const val CH_INFO = "info"

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
            if (s.phase == Phase.WAITING) "Микрофон включён, сохранять буду только ночные фразы"
            else "Сохраняю только то, что вы скажете"
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
            .setSilent(true)
            .setTimeoutAfter(60 * 60 * 1000L)
            .build()
        notify(ctx, START_ID, n)
    }

    fun cancelStartPrompt(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(START_ID)

    fun morning(ctx: Context, clips: Int) {
        val n = NotificationCompat.Builder(ctx, CH_INFO)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("За ночь: ${phrases(clips)}")
            .setContentText("Нажмите, чтобы послушать")
            .setContentIntent(openApp(ctx, records = true))
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
        ) return
        NotificationManagerCompat.from(ctx).notify(id, n)
    }
}

fun phrases(n: Int): String {
    val m10 = n % 10
    val m100 = n % 100
    val word = when {
        m10 == 1 && m100 != 11 -> "фраза"
        m10 in 2..4 && m100 !in 12..14 -> "фразы"
        else -> "фраз"
    }
    return "$n $word"
}
