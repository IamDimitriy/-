package sonnik.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf

class MainActivity : ComponentActivity() {
    /** TAB_NIGHT, TAB_RECORDS or TAB_DREAMS. Changed by the morning notification. */
    private val tab = mutableIntStateOf(0)
    private val editing = mutableStateOf<DreamEdit?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is always dark, so system bar icons stay light whatever the phone's theme.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        Notifications.createChannels(this)
        Scheduler.sync(this)
        // A rebuilt activity (text or display size, the dark theme switching at sunrise, the phone
        // freeing memory) gets its first intent again: handling it twice would open an empty dream.
        if (savedInstanceState == null) handle(intent) else restore(savedInstanceState)
        setContent { SonnikTheme { App(tab, editing) } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, tab.intValue)
        editing.value?.let { outState.putString(STATE_DREAM, it.dream.id) }
    }

    /** Reopens the dream being written; the editor saved it when the app went to the background. */
    private fun restore(state: Bundle) {
        tab.intValue = state.getInt(STATE_TAB, tab.intValue)
        val id = state.getString(STATE_DREAM) ?: return
        // No file means the dream was still empty.
        editing.value = DreamEdit(DreamStore.get(this, id) ?: DreamStore.new(), listen = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        if (intent.hasExtra(EXTRA_RECORDS)) {
            tab.intValue = if (intent.getBooleanExtra(EXTRA_RECORDS, false)) TAB_RECORDS else TAB_NIGHT
        }
        if (intent.getBooleanExtra(EXTRA_NEW_DREAM, false)) {
            // Straight after waking: open a new dream and start listening.
            val text = if (BuildConfig.DEBUG) intent.getStringExtra("demo_dream_text").orEmpty() else ""
            editing.value = DreamEdit(DreamStore.new(text = text), listen = text.isEmpty())
            intent.removeExtra(EXTRA_NEW_DREAM)
        }
        if (BuildConfig.DEBUG) DemoHooks.handle(this, intent)
    }

    companion object {
        const val EXTRA_RECORDS = "records"
        const val EXTRA_NEW_DREAM = "new_dream"
        private const val STATE_TAB = "tab"
        private const val STATE_DREAM = "dream"
    }
}
