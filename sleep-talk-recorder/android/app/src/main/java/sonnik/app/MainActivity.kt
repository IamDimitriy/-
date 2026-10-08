package sonnik.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf

class MainActivity : ComponentActivity() {
    /** 0 = night, 1 = recordings. Changed by the morning notification. */
    private val tab = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is always dark, so system bar icons stay light whatever the phone's theme.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        Notifications.createChannels(this)
        Scheduler.sync(this)
        if (intent.getBooleanExtra(EXTRA_RECORDS, false)) tab.intValue = 1
        setContent { SonnikTheme { App(tab) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_RECORDS, false)) tab.intValue = 1
    }

    companion object {
        const val EXTRA_RECORDS = "records"
    }
}
