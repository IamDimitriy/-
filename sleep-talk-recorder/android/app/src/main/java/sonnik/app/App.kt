package sonnik.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sonnik.core.Dream

const val TAB_NIGHT = 0
const val TAB_RECORDS = 1
const val TAB_DREAMS = 2

/** A dream open in the editor; [listen] starts dictation right away (after the alarm). */
data class DreamEdit(val dream: Dream, val listen: Boolean)

@Composable
fun App(
    tab: MutableIntState,
    editing: MutableState<DreamEdit?>,
    dictation: Dictation? = null,
) {
    val ctx = LocalContext.current
    val dict = dictation ?: remember { SpeechDictation(ctx) }
    val edit = editing.value
    if (edit != null) {
        // Full screen, without the tab bar: the first thing seen after waking up.
        Scaffold(containerColor = Palette.ink) { padding ->
            Box(Modifier.padding(padding)) {
                DreamEditor(edit.dream, dict, autoListen = edit.listen, onClose = {
                    editing.value = null
                    tab.intValue = TAB_DREAMS
                })
            }
        }
        return
    }
    val rec by Recorder.state.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = Palette.ink,
        bottomBar = {
            NavigationBar(containerColor = Palette.panel) {
                NavigationBarItem(
                    selected = tab.intValue == TAB_NIGHT,
                    onClick = { tab.intValue = TAB_NIGHT },
                    icon = { Icon(Glyphs.Moon, null) },
                    label = { Text("Ночь") },
                )
                NavigationBarItem(
                    selected = tab.intValue == TAB_RECORDS,
                    onClick = { tab.intValue = TAB_RECORDS },
                    icon = {
                        BadgedBox(badge = { if (rec.clips > 0) Badge { Text("${rec.clips}") } }) {
                            Icon(Glyphs.Waves, null)
                        }
                    },
                    label = { Text("Записи") },
                )
                NavigationBarItem(
                    selected = tab.intValue == TAB_DREAMS,
                    onClick = { tab.intValue = TAB_DREAMS },
                    icon = { Icon(Glyphs.Cloud, null) },
                    label = { Text("Сны") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab.intValue) {
                TAB_RECORDS -> RecordsScreen()
                TAB_DREAMS -> DreamsScreen(onOpen = { d -> editing.value = DreamEdit(d ?: DreamStore.new(), listen = false) })
                else -> NightScreen(onOpenRecords = { tab.intValue = TAB_RECORDS })
            }
        }
    }
}
