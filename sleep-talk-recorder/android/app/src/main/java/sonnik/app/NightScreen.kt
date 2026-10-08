package sonnik.app

import android.app.Activity
import android.app.TimePickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import sonnik.core.NightWindow
import java.time.Duration
import java.time.LocalDateTime

/** Slider position (right = more sensitive) <-> dB threshold above the room's silence. */
private const val SENS_SUM = 22

@Composable
fun NightScreen(onOpenRecords: () -> Unit, liveClock: Boolean = true) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val state by Recorder.state.collectAsStateWithLifecycle()

    var auto by remember { mutableStateOf(prefs.autoStart) }
    var start by remember { mutableIntStateOf(prefs.startMinute) }
    var end by remember { mutableIntStateOf(prefs.endMinute) }
    var sensitivity by remember { mutableFloatStateOf((SENS_SUM - prefs.threshold).toFloat()) }
    var anySound by remember { mutableStateOf(prefs.anySound) }
    var setup by remember { mutableStateOf(Setup.items(ctx, auto)) }
    var skipped by remember { mutableStateOf(Scheduler.isTonightSkipped(ctx)) }

    LifecycleResumeEffect(Unit) {
        // Settings can change outside this screen (notification, another window), so re-read them.
        auto = prefs.autoStart
        start = prefs.startMinute
        end = prefs.endMinute
        anySound = prefs.anySound
        sensitivity = (SENS_SUM - prefs.threshold).toFloat()
        skipped = Scheduler.isTonightSkipped(ctx)
        setup = Setup.items(ctx, prefs.autoStart)
        onPauseOrDispose { }
    }
    val now by produceState(LocalDateTime.now()) {
        while (liveClock) { delay(15_000); value = LocalDateTime.now() }
    }

    var askedPermission by remember { mutableStateOf<String?>(null) }
    var startAfterGrant by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val p = askedPermission
        if (!granted && p != null && ctx is Activity && !ctx.shouldShowRequestPermissionRationale(p)) {
            // Denied twice: Android will not ask again, the switch is in app settings.
            ctx.startActivity(Setup.settingsIntent(ctx, "app"))
        }
        if (granted && startAfterGrant) Recorder.start(ctx, now = true)
        startAfterGrant = false
        setup = Setup.items(ctx, auto)
    }

    fun fix(item: SetupItem) {
        val perm = item.permission
        if (perm != null) {
            askedPermission = perm
            permLauncher.launch(perm)
        } else {
            runCatching { ctx.startActivity(Setup.settingsIntent(ctx, item.id)) }
                .onFailure { ctx.startActivity(Setup.settingsIntent(ctx, "app")) }
        }
    }

    fun startNow() {
        val mic = setup.first { it.id == "mic" }
        if (mic.ok) Recorder.start(ctx, now = true)
        else { startAfterGrant = true; fix(mic) }
    }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Сонник", fontSize = 32.sp, fontWeight = FontWeight.SemiBold)

        StatusCard(
            state = state, auto = auto, skipped = skipped, startMinute = start, endMinute = end, now = now,
            onStartNow = ::startNow,
            onStop = { Recorder.stop(ctx) },
            onCancelWaiting = {
                // "Not tonight": also keep the midnight auto-start from switching it back on.
                Recorder.stop(ctx)
                if (auto) { Scheduler.skipTonight(ctx, true); skipped = true }
            },
            onSkip = { Scheduler.skipTonight(ctx, it); skipped = it },
            onOpenRecords = onOpenRecords,
        )

        val missing = setup.filter { !it.ok }
        if (missing.isNotEmpty()) SetupCard(missing, ::fix)

        Section("Расписание") {
            SettingRow("Включать само каждую ночь", "Телефон можно заблокировать и положить экраном вниз") {
                Switch(checked = auto, onCheckedChange = {
                    auto = it; prefs.autoStart = it; Scheduler.sync(ctx); setup = Setup.items(ctx, it)
                    skipped = Scheduler.isTonightSkipped(ctx)
                })
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TimeButton("Начало", start, Modifier.weight(1f)) {
                    start = it; prefs.startMinute = it; prefs.skippedStart = null; Scheduler.sync(ctx); skipped = false
                }
                TimeButton("Конец", end, Modifier.weight(1f)) {
                    end = it; prefs.endMinute = it; Scheduler.sync(ctx)
                }
            }
        }

        Section("Чувствительность") {
            Slider(
                value = sensitivity,
                onValueChange = { sensitivity = it },
                onValueChangeFinished = { prefs.threshold = SENS_SUM - sensitivity.roundToInt() },
                valueRange = 4f..18f,
                steps = 13,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("только громкая речь", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                Text("даже шёпот", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            SettingRow("Записывать любые звуки", "Вместе с храпом, скрипом и кашлем") {
                Switch(checked = anySound, onCheckedChange = { anySound = it; prefs.anySound = it })
            }
            if (state.phase != Phase.IDLE) {
                Text(
                    "Изменения заработают со следующего запуска.",
                    style = MaterialTheme.typography.bodySmall, color = Palette.muted,
                )
            }
        }

        Text(
            "Записи хранятся только на этом телефоне. Микрофон слушает всю ночь, " +
                "но сохраняются лишь фрагменты, где звучит речь.",
            style = MaterialTheme.typography.bodySmall, color = Palette.muted,
        )
    }
}

@Composable
private fun StatusCard(
    state: RecorderState,
    auto: Boolean,
    skipped: Boolean,
    startMinute: Int,
    endMinute: Int,
    now: LocalDateTime,
    onStartNow: () -> Unit,
    onStop: () -> Unit,
    onCancelWaiting: () -> Unit,
    onSkip: (Boolean) -> Unit,
    onOpenRecords: () -> Unit,
) {
    val window = NightWindow(Prefs.minuteToTime(startMinute), Prefs.minuteToTime(endMinute))
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Palette.panel),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (state.phase) {
                Phase.RECORDING -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PulseDot()
                        Spacer(Modifier.width(10.dp))
                        Text("Слушаю", style = MaterialTheme.typography.headlineSmall)
                    }
                    Text(
                        "До ${Notifications.time(state.stopAt)} · " +
                            if (state.clips == 0) "фраз пока нет"
                            else "${phrases(state.clips)}, последняя в ${Notifications.time(state.lastClipAt)}",
                        color = Palette.muted,
                    )
                    LinearProgressIndicator(
                        progress = { state.level },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                        color = Palette.amber, trackColor = Palette.line,
                        drawStopIndicator = {},
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onStop) { Text("Остановить") }
                        if (state.clips > 0) TextButton(onClick = onOpenRecords) { Text("Послушать") }
                    }
                }
                Phase.WAITING -> {
                    Text("Жду ${Notifications.time(state.saveFrom)}", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Микрофон уже включён и привыкает к тишине комнаты. Сохраню только то, " +
                            "что прозвучит после ${Notifications.time(state.saveFrom)}.",
                        color = Palette.muted,
                    )
                    OutlinedButton(onClick = onCancelWaiting) { Text("Не записывать эту ночь") }
                }
                Phase.IDLE -> {
                    val inWindow = window.contains(now)
                    val next = window.nextStart(now)
                    when {
                        inWindow -> {
                            Text("Сейчас ночь, но запись не идёт", style = MaterialTheme.typography.headlineSmall)
                            Text("Нажмите, чтобы слушать до ${Prefs.format(endMinute)}.", color = Palette.muted)
                            Button(onClick = onStartNow) { Text("Начать запись") }
                        }
                        auto && skipped -> {
                            Text("Эту ночь пропускаю", style = MaterialTheme.typography.headlineSmall)
                            Text(
                                "Следующая запись начнётся сама ${dayWord(now, window.nextStart(next))} " +
                                    "в ${Prefs.format(startMinute)}.",
                                color = Palette.muted,
                            )
                            OutlinedButton(onClick = { onSkip(false) }) { Text("Всё-таки записать") }
                        }
                        auto -> {
                            Text("Запись начнётся сама в ${Prefs.format(startMinute)}", style = MaterialTheme.typography.headlineSmall)
                            Text(
                                "${until(now, next)} · до ${Prefs.format(endMinute)}. " +
                                    "Поставьте телефон на зарядку рядом с кроватью и спите.",
                                color = Palette.muted,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = onStartNow) { Text("Начать сейчас") }
                                TextButton(onClick = { onSkip(true) }) { Text("Пропустить ночь") }
                            }
                        }
                        else -> {
                            Text("Автозапуск выключен", style = MaterialTheme.typography.headlineSmall)
                            Text("Включите его в расписании или начните запись вручную.", color = Palette.muted)
                            Button(onClick = onStartNow) { Text("Начать сейчас") }
                        }
                    }
                }
            }
        }
    }
}

internal fun dayWord(now: LocalDateTime, at: LocalDateTime): String =
    when (Duration.between(now.toLocalDate().atStartOfDay(), at.toLocalDate().atStartOfDay()).toDays()) {
        0L -> "сегодня"
        1L -> "завтра"
        2L -> "послезавтра"
        else -> at.toLocalDate().toString()
    }

internal fun until(now: LocalDateTime, at: LocalDateTime): String {
    val m = Duration.between(now, at).toMinutes().coerceAtLeast(0)
    return when {
        m < 1 -> "Через минуту"
        m < 60 -> "Через $m мин"
        m % 60 == 0L -> "Через ${m / 60} ч"
        else -> "Через ${m / 60} ч ${m % 60} мин"
    }
}

@Composable
private fun PulseDot() {
    val t = rememberInfiniteTransition(label = "pulse")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(12.dp).alpha(a).clip(CircleShape).background(Palette.amber))
}

@Composable
private fun SetupCard(items: List<SetupItem>, onFix: (SetupItem) -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Palette.panelHigh),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Чтобы всё работало само", style = MaterialTheme.typography.titleMedium)
            items.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.title, fontWeight = FontWeight.Medium)
                        Text(item.why, style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = { onFix(item) }) { Text("Разрешить") }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Palette.panel),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) { content() }
        }
    }
}

@Composable
private fun SettingRow(title: String, hint: String, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = Palette.muted)
        }
        Spacer(Modifier.width(12.dp))
        control()
    }
}

@Composable
private fun TimeButton(label: String, minute: Int, modifier: Modifier, onPick: (Int) -> Unit) {
    val ctx = LocalContext.current
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.panelHigh)
            .padding(0.dp),
    ) {
        TextButton(
            onClick = {
                TimePickerDialog(ctx, { _, h, m -> onPick(h * 60 + m) }, minute / 60, minute % 60, true).show()
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                Text(Prefs.format(minute), fontSize = 28.sp, color = Color(0xFFE6E2D6), fontWeight = FontWeight.Light)
            }
        }
    }
}
