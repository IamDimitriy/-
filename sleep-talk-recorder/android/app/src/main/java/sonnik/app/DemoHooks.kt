package sonnik.app

import android.content.Context
import android.content.Intent
import java.time.LocalTime

/**
 * Debug-build commands for the scripted emulator demo (see android/demo/run-demo.sh), sent as
 * extras to MainActivity with `adb shell am start`. Release builds ignore them.
 */
object DemoHooks {
    fun handle(ctx: Context, intent: Intent) {
        intent.getStringExtra("demo_wav")?.let { Recorder.start(ctx, now = true, demoWav = it) }
        if (intent.getBooleanExtra("demo_stop", false)) Recorder.stop(ctx)
        val inMinutes = intent.getIntExtra("demo_schedule_in", -1)
        if (inMinutes >= 0) {
            val prefs = Prefs(ctx)
            val start = LocalTime.now().plusMinutes(inMinutes.toLong())
            val startMinute = start.hour * 60 + start.minute
            prefs.startMinute = startMinute
            prefs.endMinute = (startMinute + intent.getIntExtra("demo_length", 3)) % (24 * 60)
            prefs.autoStart = true
            prefs.skippedStart = null
            Scheduler.sync(ctx)
        }
    }
}
