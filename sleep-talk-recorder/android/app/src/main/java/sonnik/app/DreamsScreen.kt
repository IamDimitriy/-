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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    // Read the state once, outside the list's content: reading it inside as well can give the
    // lazy list two different snapshots (item count from one, keys from the other) while the
    // journal is loading, which crashes with IndexOutOfBoundsException.
    val list = dreams
    val shown = list.orEmpty().filter { DreamSearch.matches(it, query) }
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
                if (!list.isNullOrEmpty()) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Поиск по снам") },
                        singleLine = true,
                    )
                }
            }
        }
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
            // The dream itself; a separate title would only repeat its first sentence.
            Text(d.text.ifBlank { d.title }, maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            if (d.notes.isNotBlank()) Text("Есть толкование", style = MaterialTheme.typography.labelSmall, color = Palette.amber)
        }
    }
}

/**
 * Writing a dream down. The mic button dictates into the text; the text stays editable.
 * A non-empty dream saves itself a second after each change, when the app goes to the
 * background and on leaving the screen: the phone may close the app at any of those moments.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DreamEditor(
    initial: Dream,
    dictation: Dictation,
    autoListen: Boolean,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // Saveable, so edits survive the screen being rebuilt (text size, dark theme at sunrise).
    var text by rememberSaveable(initial.id) { mutableStateOf(initial.text) }
    var partial by remember { mutableStateOf("") }
    var moodId by rememberSaveable(initial.id) { mutableStateOf(initial.mood?.id) }
    var notes by rememberSaveable(initial.id) { mutableStateOf(initial.notes) }
    var listening by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    /** Closed or deleted: nothing may save the dream after that. */
    var closed by remember { mutableStateOf(false) }
    val night = remember(initial.id) { DreamStore.nightOf(ctx, initial) }
    val mood = DreamMood.byId(moodId)

    // Reads the state when called: the pause callback below keeps the first composition's functions.
    fun current() = initial.copy(text = text.trim(), mood = DreamMood.byId(moodId), notes = notes.trim())

    // On the main thread, like close() and delete: a write still running on another thread
    // could bring back a dream deleted a moment later. The file is small.
    fun persist() {
        if (closed) return
        val d = current()
        when {
            d.text.isNotBlank() || d.notes.isNotBlank() -> DreamStore.save(ctx, d)
            // A new dream autosaved and then erased again should not stay in the journal.
            initial.text.isBlank() && initial.notes.isBlank() -> DreamStore.delete(ctx, d)
        }
    }

    fun close() {
        dictation.stop()
        persist()
        closed = true
        onClose()
    }

    fun startListening() {
        problem = null
        listening = true
        // A night recording still running must not keep the dream being told as sleep talk.
        Recorder.awake()
        dictation.start(
            onPartial = { partial = it; Recorder.awake() },
            onFinal = { words ->
                Recorder.awake()
                text = if (text.isBlank()) words.replaceFirstChar { it.uppercase() } else "${text.trimEnd()} $words"
            },
            onStopped = { err -> listening = false; problem = err; Recorder.awake() },
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
    // Autosave once typing or dictation pauses for a second.
    LaunchedEffect(text, moodId, notes) {
        delay(1_000)
        persist()
    }
    // Leaving for another app (Claude, ChatGPT) or the screen being rebuilt.
    LifecycleResumeEffect(Unit) {
        onPauseOrDispose { persist() }
    }

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
            FilledTonalButton(onClick = ::close) { Text("Готово") }
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
            // A hint about what to do instead, not an error.
            problem?.let { Text(it, color = Palette.muted, style = MaterialTheme.typography.bodySmall) }
        }

        OutlinedTextField(
            value = text, onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
            label = { Text("Что снилось") },
        )

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Каким был сон", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
            // Wraps on a narrow screen or with large text instead of cutting off "Кошмар".
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in DreamMood.entries) {
                    FilterChip(
                        selected = mood == m,
                        onClick = { moodId = if (mood == m) null else m.id },
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
                    onClick = {
                        // Saved first: the phone may close Сонник while the user reads the answer.
                        persist()
                        shareForInterpretation(ctx, current(), DreamStore.factsOf(night))
                    },
                    enabled = text.isNotBlank(),
                ) { Text("Толковать в Claude или ChatGPT") }
                OutlinedTextField(
                    value = notes, onValueChange = { notes = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                    label = { Text("Толкование и мысли") },
                )
                // The answer copied in Claude or ChatGPT, added after what is already written.
                TextButton(onClick = {
                    val answer = clipboard.getText()?.text?.trim().orEmpty()
                    if (answer.isNotEmpty()) {
                        notes = if (notes.isBlank()) answer else "${notes.trimEnd()}\n\n$answer"
                    }
                }) { Text("Вставить ответ из буфера") }
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
                    closed = true
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
