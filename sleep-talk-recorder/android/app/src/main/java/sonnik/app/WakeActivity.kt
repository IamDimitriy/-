package sonnik.app

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

/**
 * Shown for a moment over the lock screen at the start of the night, or after a tap on the Quick
 * Settings tile ([SleepTile]). Being on screen is what allows the microphone service to start;
 * then it closes and the screen goes dark again.
 */
class WakeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        // Keep the flash as dim as possible.
        window.attributes = window.attributes.apply { screenBrightness = 0.01f }
        setContent {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Text("Включаю запись сна", color = Color(0xFF7A4A2A), fontSize = 18.sp)
            }
        }
    }

    private var started = false

    override fun onResume() {
        super.onResume()
        if (started) return
        started = true
        Log.i("Sonnik", "Wake screen shown, starting the recorder")
        Notifications.cancelStartPrompt(this)
        // The tile means "from now on"; the midnight start waits for the night window.
        val now = intent.getBooleanExtra(EXTRA_NOW, false)
        if (Recorder.state.value.phase == Phase.IDLE) Recorder.start(this, now = now)
        window.decorView.postDelayed({ finish() }, 1500)
    }

    companion object {
        const val EXTRA_NOW = "now"
    }
}
