package sonnik.app

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ru = Locale("ru")
private val dayFmt = DateTimeFormatter.ofPattern("d MMMM", ru)
private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
private val secFmt = DateTimeFormatter.ofPattern("HH:mm:ss")

/** One clip plays at a time. */
private class ClipPlayer {
    var current by mutableStateOf<File?>(null)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    private var mp: MediaPlayer? = null

    fun toggle(f: File) {
        val same = current == f
        stop()
        if (same) return
        mp = runCatching {
            MediaPlayer().apply {
                setDataSource(f.path)
                setOnCompletionListener { stop() }
                prepare()
                start()
            }
        }.getOrNull()
        if (mp != null) current = f
    }

    fun tick() {
        val p = mp ?: return
        if (p.duration > 0) progress = p.currentPosition.toFloat() / p.duration
    }

    fun stop() {
        mp?.release()
        mp = null
        current = null
        progress = 0f
    }
}

private sealed interface PendingDelete {
    data class OneClip(val clip: Clip) : PendingDelete
    data class WholeNight(val night: Night) : PendingDelete
}

@Composable
fun RecordsScreen() {
    val ctx = LocalContext.current
    val rec by Recorder.state.collectAsStateWithLifecycle()
    var nights by remember { mutableStateOf<List<Night>?>(null) }
    var version by remember { mutableStateOf(0) }
    val player = remember { ClipPlayer() }
    var pending by remember { mutableStateOf<PendingDelete?>(null) }

    LaunchedEffect(version, rec.clips) {
        nights = withContext(Dispatchers.IO) { Nights.list(ctx) }
    }
    LifecycleResumeEffect(Unit) {
        version++
        onPauseOrDispose { player.stop() }
    }
    LaunchedEffect(player.current) {
        while (player.current != null) { player.tick(); delay(100) }
    }
    DisposableEffect(Unit) { onDispose { player.stop() } }

    val list = nights
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text("Записи", fontSize = 32.sp, fontWeight = FontWeight.SemiBold) }
        if (list != null && list.isEmpty()) item {
            Text(
                "Здесь появятся ночи. Каждая — список фраз со временем, которые можно послушать и отправить.",
                color = Palette.muted,
            )
        }
        items(list.orEmpty(), key = { it.dir.name }) { night ->
            NightCard(
                night = night,
                live = rec.phase == Phase.RECORDING && night == list?.firstOrNull(),
                player = player,
                onShare = { share(ctx, it) },
                onDeleteClip = { pending = PendingDelete.OneClip(it) },
                onDeleteNight = { pending = PendingDelete.WholeNight(night) },
            )
        }
    }

    pending?.let { p ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(if (p is PendingDelete.WholeNight) "Удалить ночь?" else "Удалить фразу?") },
            text = {
                Text(
                    if (p is PendingDelete.WholeNight) "Все ${phrases(p.night.clips.size)} этой ночи пропадут насовсем."
                    else "Запись пропадёт насовсем."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    player.stop()
                    when (p) {
                        is PendingDelete.OneClip -> Nights.delete(p.clip)
                        is PendingDelete.WholeNight -> Nights.delete(p.night)
                    }
                    pending = null
                    version++
                }) { Text("Удалить", color = Palette.danger) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Оставить") } },
        )
    }
}

@Composable
private fun NightCard(
    night: Night,
    live: Boolean,
    player: ClipPlayer,
    onShare: (Clip) -> Unit,
    onDeleteClip: (Clip) -> Unit,
    onDeleteNight: () -> Unit,
) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Palette.panel)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("Ночь на ${night.morning.format(dayFmt)}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "с ${night.start.format(timeFmt)} · " +
                            (if (night.clips.isEmpty()) "тихо" else phrases(night.clips.size)) +
                            if (live) " · идёт запись" else "",
                        style = MaterialTheme.typography.bodySmall, color = Palette.muted,
                    )
                }
                if (!live) TextButton(onClick = onDeleteNight) { Text("Удалить", color = Palette.muted) }
            }
            if (night.clips.isEmpty()) {
                Text(
                    if (live) "Пока тишина." else "Ни одной фразы. Если вы точно говорили, прибавьте чувствительность.",
                    color = Palette.muted, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            night.clips.forEachIndexed { i, clip ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 76.dp))
                ClipRow(clip, player, onShare, onDeleteClip)
            }
        }
    }
}

@Composable
private fun ClipRow(clip: Clip, player: ClipPlayer, onShare: (Clip) -> Unit, onDelete: (Clip) -> Unit) {
    val playing = player.current == clip.file
    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = { player.toggle(clip.file) }) {
                Icon(if (playing) Glyphs.Pause else Glyphs.Play, if (playing) "Пауза" else "Слушать")
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(clip.at.format(secFmt), color = Palette.amber, fontWeight = FontWeight.SemiBold)
                Text("%.0f с".format(clip.durationS), style = MaterialTheme.typography.bodySmall, color = Palette.muted)
            }
            IconButton(onClick = { onShare(clip) }) { Icon(Icons.Filled.Share, "Отправить", tint = Palette.muted) }
            IconButton(onClick = { onDelete(clip) }) { Icon(Icons.Filled.Delete, "Удалить", tint = Palette.muted) }
        }
        if (playing) {
            LinearProgressIndicator(
                progress = { player.progress },
                modifier = Modifier.fillMaxWidth().padding(start = 60.dp, end = 8.dp).height(3.dp).clip(CircleShape),
                color = Palette.amber, trackColor = Palette.line,
                drawStopIndicator = {},
            )
        }
    }
}

private fun share(ctx: Context, clip: Clip) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", clip.file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("audio/wav")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, "Сонник, ${clip.at.format(dayFmt)} ${clip.at.format(timeFmt)}")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, "Отправить запись"))
}
