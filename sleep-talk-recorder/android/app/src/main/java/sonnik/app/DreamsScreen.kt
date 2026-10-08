package sonnik.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sonnik.core.Dream
import sonnik.core.DreamMood
import sonnik.core.DreamPrompt
import sonnik.core.DreamSearch
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dreamDay = DateTimeFormatter.ofPattern("d MMMM, EEEE", Locale("ru"))

private fun moodColor(m: DreamMood) = when (m) {
    DreamMood.GOOD -> Palette.ok
    DreamMood.NEUTRAL -> Palette.muted
    DreamMood.ANXIOUS -> Palette.amber
    DreamMood.NIGHTMARE -> Palette.danger
}

/** The dream journal: a searchable list; [onOpen] opens a dream (or a new one, with null). */
@Composable
fun DreamsScreen(onOpen: (Dream?) -> Unit) {
    val ctx = LocalContext.current
    var dreams by remember { mutableStateOf<List<Dream>?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { dreams = withContext(Dispatchers.IO) { DreamStore.list(ctx) } }

    val shown = dreams.orEmpty().filter { DreamSearch.matches(it, query) }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Сны", fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
                Button(
                    onClick = { onOpen(null) },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                ) { Text("Записать сон", fontSize = 17.sp) }
                if (!dreams.isNullOrEmpty()) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Поиск по снам") },
                        singleLine = true,
                    )
                }
            }
        }
        val list = dreams
        if (list != null && list.isEmpty()) item {
            Text(
                "Записывайте сны сразу после пробуждения, пока они не забылись: голосом или текстом. " +
                    "Здесь будет ваш дневник снов с поиском, а каждый сон можно отправить на толкование " +
                    "в Claude или ChatGPT.",
                color = Palette.muted,
            )
        }
        if (list != null && list.isNotEmpty() && shown.isEmpty()) item {
            Text("Ничего не нашлось.", color = Palette.muted)
        }
        items(shown, key = { it.id }) { d -> DreamCard(d) { onOpen(d) } }
    }
}

@Composable
private fun DreamCard(d: Dream, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Palette.panel),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.createdAt.format(dreamDay), style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                d.mood?.let {
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.size(7.dp).clip(CircleShape).background(moodColor(it)))
                    Spacer(Modifier.width(5.dp))
                    Text(it.title, style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                }
            }
            Text(d.title, style = MaterialTheme.typography.titleMedium)
            // The rest of the dream after the first sentence, so the title is not repeated.
            val rest = d.text.trim().removePrefix(d.title.removeSuffix("…")).trimStart('.', '!', '?', ',', ' ', '\n')
            if (rest.isNotBlank() && !d.title.endsWith("…")) {
                Text(rest, maxLines = 3, overflow = TextOverflow.Ellipsis, color = Palette.muted)
            }
            if (d.notes.isNotBlank()) Text("Есть толкование", style = MaterialTheme.typography.labelSmall, color = Palette.amber)
        }
    }
}

/**
 * Writing a dream down. The mic button dictates into the text; the text stays editable.
 * Leaving the screen saves a non-empty dream.
 */
@Composable
fun DreamEditor(
    initial: Dream,
    dictation: Dictation,
    autoListen: Boolean,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf(initial.text) }
    var partial by remember { mutableStateOf("") }
    var mood by remember { mutableStateOf(initial.mood) }
    var notes by remember { mutableStateOf(initial.notes) }
    var listening by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val night = remember(initial.id) { DreamStore.nightOf(ctx, initial) }

    fun current() = initial.copy(text = text.trim(), mood = mood, notes = notes.trim())

    fun close() {
        dictation.stop()
        val d = current()
        if (d.text.isNotBlank() || d.notes.isNotBlank()) DreamStore.save(ctx, d)
        onClose()
    }

    fun startListening() {
        problem = null
        listening = true
        dictation.start(
            onPartial = { partial = it },
            onFinal = { words ->
                text = if (text.isBlank()) words.replaceFirstChar { it.uppercase() } else "${text.trimEnd()} $words"
            },
            onStopped = { err -> listening = false; problem = err },
        )
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startListening() else problem = "Без доступа к микрофону сон можно записать только текстом."
    }

    fun toggleMic() {
        if (listening) {
            dictation.stop(); listening = false; partial = ""
        } else if (!dictation.available) {
            problem = "На этом телефоне нет распознавания речи. Можно надиктовать с клавиатуры (значок микрофона) или написать текстом."
        } else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(Unit) { if (autoListen && initial.text.isBlank()) toggleMic() }
    DisposableEffect(Unit) { onDispose { dictation.stop() } }
    BackHandler { close() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.ink)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (initial.text.isBlank()) "Запомни сон" else "Сон", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                Text(initial.createdAt.format(dreamDay), color = Palette.muted)
            }
            TextButton(onClick = ::close) { Text("Готово") }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(if (listening) Palette.amber else Palette.panelHigh)
                    .clickable(onClick = ::toggleMic),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (listening) "Стоп" else "Говорить", color = if (listening) Palette.amberInk else Palette.amber, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    listening && partial.isNotBlank() -> partial
                    listening -> "Слушаю. Рассказывайте, паузы не страшны"
                    else -> "Нажмите и расскажите сон, пока он не забылся"
                },
                color = Palette.muted, style = MaterialTheme.typography.bodyMedium,
            )
            problem?.let { Text(it, color = Palette.danger, style = MaterialTheme.typography.bodySmall) }
        }

        OutlinedTextField(
            value = text, onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
            label = { Text("Что снилось") },
        )

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Каким был сон", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in DreamMood.entries) {
                    FilterChip(
                        selected = mood == m,
                        onClick = { mood = if (mood == m) null else m },
                        label = { Text(m.title) },
                        leadingIcon = { Box(Modifier.size(8.dp).clip(CircleShape).background(moodColor(m))) },
                    )
                }
            }
        }

        night?.let { n ->
            val s = n.summary
            val facts = buildList {
                add(phrases(n.phrases))
                if (n.sounds > 0) add(soundsText(n.sounds))
                if (s.snoreMinutes > 0) add("храп ${minutesText(s.snoreMinutes)}")
            }.joinToString(" · ")
            Text("Этой ночью: $facts", color = Palette.muted, style = MaterialTheme.typography.bodySmall)
        }

        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Palette.panel)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Толкование", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Отправит сон с готовым вопросом в Claude, ChatGPT или другое приложение по вашей подписке. " +
                        "Ответ можно вставить ниже, чтобы он остался в дневнике.",
                    color = Palette.muted, style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = { shareForInterpretation(ctx, current(), DreamStore.factsOf(night)) },
                    enabled = text.isNotBlank(),
                ) { Text("Толковать в Claude или ChatGPT") }
                OutlinedTextField(
                    value = notes, onValueChange = { notes = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                    label = { Text("Толкование и мысли") },
                )
            }
        }

        if (initial.text.isNotBlank()) {
            TextButton(onClick = { confirmDelete = true }) { Text("Удалить сон", color = Palette.danger) }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить сон?") },
            text = { Text("Запись и толкование пропадут насовсем.") },
            confirmButton = {
                TextButton(onClick = {
                    dictation.stop()
                    DreamStore.delete(ctx, initial)
                    confirmDelete = false
                    onClose()
                }) { Text("Удалить", color = Palette.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Оставить") } },
        )
    }
}

fun shareForInterpretation(ctx: Context, d: Dream, night: sonnik.core.NightFacts?) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, DreamPrompt.build(d, night))
    ctx.startActivity(Intent.createChooser(send, "Толковать сон в…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
