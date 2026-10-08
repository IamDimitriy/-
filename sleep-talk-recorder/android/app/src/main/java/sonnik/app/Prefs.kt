package sonnik.app

import android.content.Context
import sonnik.core.NightWindow
import java.time.LocalDateTime
import java.time.LocalTime

/** User settings, kept in SharedPreferences. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var autoStart: Boolean
        get() = sp.getBoolean("auto", true)
        set(v) = sp.edit().putBoolean("auto", v).apply()

    var startMinute: Int
        get() = sp.getInt("start", 0)
        set(v) = sp.edit().putInt("start", v).apply()

    var endMinute: Int
        get() = sp.getInt("end", 7 * 60)
        set(v) = sp.edit().putInt("end", v).apply()

    /** How many dB above the room's silence a sound must be. Lower = more sensitive. */
    var threshold: Int
        get() = sp.getInt("threshold", 10)
        set(v) = sp.edit().putInt("threshold", v).apply()

    var anySound: Boolean
        get() = sp.getBoolean("anySound", false)
        set(v) = sp.edit().putBoolean("anySound", v).apply()

    /** Start of a night the user chose to skip (ISO date-time), or null. */
    var skippedStart: LocalDateTime?
        get() = sp.getString("skip", null)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
        set(v) = sp.edit().putString("skip", v?.toString()).apply()

    var alarmOn: Boolean
        get() = sp.getBoolean("alarm", false)
        set(v) = sp.edit().putBoolean("alarm", v).apply()

    /** Latest wake-up time, minutes after midnight. */
    var alarmMinute: Int
        get() = sp.getInt("alarmAt", 7 * 60)
        set(v) = sp.edit().putInt("alarmAt", v).apply()

    /** How many minutes before [alarmMinute] the smart alarm may ring in light sleep (0 = plain alarm). */
    var alarmWindow: Int
        get() = sp.getInt("alarmWindow", 30)
        set(v) = sp.edit().putInt("alarmWindow", v).apply()

    /** The alarm time that already rang (ISO date-time), so the backup alarm does not ring twice. */
    var rangFor: LocalDateTime?
        get() = sp.getString("rangFor", null)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
        set(v) = sp.edit().putString("rangFor", v?.toString()).apply()

    val alarmTime: LocalTime get() = minuteToTime(alarmMinute)

    val window: NightWindow
        get() = NightWindow(minuteToTime(startMinute), minuteToTime(endMinute))

    companion object {
        fun minuteToTime(m: Int): LocalTime = LocalTime.of(m / 60, m % 60)
        fun format(m: Int) = "%02d:%02d".format(m / 60, m % 60)
    }
}
