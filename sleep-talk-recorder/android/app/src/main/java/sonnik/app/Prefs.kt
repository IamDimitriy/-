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

    val window: NightWindow
        get() = NightWindow(minuteToTime(startMinute), minuteToTime(endMinute))

    companion object {
        fun minuteToTime(m: Int): LocalTime = LocalTime.of(m / 60, m % 60)
        fun format(m: Int) = "%02d:%02d".format(m / 60, m % 60)
    }
}
