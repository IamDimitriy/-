package sonnik.app

import android.content.Context
import android.util.Log
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * A short diary of the night's automatic steps (alarm fired, screen opened, recording started
 * or refused and why), kept on the phone so a night that did not record can be explained
 * without a computer. Shown on the night screen and shareable.
 */
object EventLog {
    private const val FILE = "events.log"
    private const val KEEP_LINES = 300
    private val stamp = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss")

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    @Synchronized
    fun add(ctx: Context, message: String, now: LocalDateTime = LocalDateTime.now()) {
        Log.i("Sonnik", message)
        runCatching {
            val f = file(ctx)
            f.appendText("${now.format(stamp)}  $message\n")
            // Kept short: only the last few nights matter.
            if (f.length() > 64 * 1024) f.writeText(f.readLines().takeLast(KEEP_LINES).joinToString("\n", postfix = "\n"))
        }
    }

    /** The newest lines first. */
    @Synchronized
    fun lines(ctx: Context, max: Int = 80): List<String> =
        runCatching { file(ctx).readLines().filter { it.isNotBlank() }.takeLast(max).reversed() }.getOrDefault(emptyList())
}
