package sonnik.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/** One thing Android must allow for the app to work by itself at night. */
data class SetupItem(
    val id: String,
    val title: String,
    val why: String,
    val ok: Boolean,
    /** Runtime permission to request, or null when the fix is a system settings screen. */
    val permission: String? = null,
)

object Setup {
    fun items(ctx: Context, autoStart: Boolean): List<SetupItem> {
        val list = mutableListOf(
            SetupItem(
                "mic", "Доступ к микрофону", "Без него записывать нечего.",
                granted(ctx, Manifest.permission.RECORD_AUDIO), Manifest.permission.RECORD_AUDIO,
            )
        )
        if (Build.VERSION.SDK_INT >= 33) list += SetupItem(
            "notif", "Уведомления", "Через уведомление запись включается ночью и останавливается утром.",
            granted(ctx, Manifest.permission.POST_NOTIFICATIONS), Manifest.permission.POST_NOTIFICATIONS,
        )
        if (autoStart && Build.VERSION.SDK_INT >= 34) list += SetupItem(
            "fullscreen", "Запуск поверх экрана блокировки",
            "Нужен, чтобы ночью включать запись и утром будить, пока телефон заблокирован.",
            runCatching { ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent() }.getOrDefault(true),
        )
        if (autoStart && !Scheduler.canExact(ctx)) list += SetupItem(
            "alarm", "Точные будильники", "Чтобы запись начиналась ровно в назначенное время.", false,
        )
        list += SetupItem(
            "battery", "Работа без ограничений батареи",
            "Иначе система может усыпить запись посреди ночи. Лучше ставить телефон на зарядку.",
            (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName),
        )
        return list
    }

    /** What is not allowed, for the event log: "" when everything is. */
    fun problems(ctx: Context): String {
        val missing = items(ctx, autoStart = true).filter { !it.ok }.map { it.title.lowercase() }
        return if (missing.isEmpty()) "" else " (не разрешено: ${missing.joinToString()})"
    }

    /** Samsung puts apps it thinks are unused to sleep, and a sleeping app misses its alarms. */
    val isSamsung: Boolean get() = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    /** Samsung's battery settings, where "never sleeping apps" are, or the closest screen there is. */
    fun samsungBatteryIntents(ctx: Context): List<Intent> = listOf(
        Intent().setClassName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")),
    )

    private fun granted(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    /** Screen that fixes a non-permission item, or the app's settings page for a denied permission. */
    @SuppressLint("BatteryLife", "InlinedApi")
    fun settingsIntent(ctx: Context, id: String): Intent {
        val pkg = Uri.parse("package:${ctx.packageName}")
        return when (id) {
            "fullscreen" -> Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)
            "alarm" -> Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg)
            "battery" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        }
    }
}
