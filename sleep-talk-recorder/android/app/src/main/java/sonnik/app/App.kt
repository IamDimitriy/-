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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun App(tab: MutableIntState) {
    val rec by Recorder.state.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = Palette.ink,
        bottomBar = {
            NavigationBar(containerColor = Palette.panel) {
                NavigationBarItem(
                    selected = tab.intValue == 0,
                    onClick = { tab.intValue = 0 },
                    icon = { Icon(Glyphs.Moon, null) },
                    label = { Text("Ночь") },
                )
                NavigationBarItem(
                    selected = tab.intValue == 1,
                    onClick = { tab.intValue = 1 },
                    icon = {
                        BadgedBox(badge = { if (rec.clips > 0) Badge { Text("${rec.clips}") } }) {
                            Icon(Glyphs.Waves, null)
                        }
                    },
                    label = { Text("Записи") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            if (tab.intValue == 0) NightScreen(onOpenRecords = { tab.intValue = 1 }) else RecordsScreen()
        }
    }
}
