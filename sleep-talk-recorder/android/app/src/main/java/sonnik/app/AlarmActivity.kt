package sonnik.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Full-screen alarm over the lock screen. The sound comes from the alarm notification. */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val smart = intent.getBooleanExtra(EXTRA_SMART, false)
        val s = Recorder.state.value
        setContent {
            SonnikTheme {
                AlarmScreen(
                    smart = smart, clips = s.clips, snoreMinutes = s.snoreMinutes,
                    onDismiss = {
                        Alarm.dismiss(this)
                        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_RECORDS, true))
                        finish()
                    },
                    onSnooze = { Alarm.snooze(this); finish() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_SMART = "smart"
    }
}

@Composable
fun AlarmScreen(smart: Boolean, clips: Int, snoreMinutes: Int, onDismiss: () -> Unit, onSnooze: () -> Unit) {
    val now = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
    val night = buildList {
        if (clips > 0) add("${phrases(clips)} во сне")
        if (snoreMinutes > 0) add("храп ${minutesText(snoreMinutes)}")
    }.joinToString(" · ")
    Column(
        Modifier.fillMaxSize().background(Palette.ink).padding(horizontal = 28.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(now, fontSize = 88.sp, fontWeight = FontWeight.Light, color = Palette.amber)
        Text("Доброе утро", fontSize = 26.sp, color = Palette.text)
        Spacer(Modifier.height(12.dp))
        Text(
            if (smart) "Сейчас сон лёгкий, проснуться будет проще" else "Время вставать",
            color = Palette.muted, textAlign = TextAlign.Center,
        )
        if (night.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("За ночь: $night", color = Palette.muted, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(48.dp))
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().height(64.dp),
            shape = RoundedCornerShape(20.dp),
        ) { Text("Выключить", fontSize = 20.sp) }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onSnooze,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(20.dp),
        ) { Text("Ещё 10 минут", fontSize = 18.sp) }
    }
}
