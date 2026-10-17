package com.kimbar.observ.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.kimbar.observ.data.TestHandle
import com.kimbar.observ.data.TestStore
import com.kimbar.observ.media.VideoFrames
import com.kimbar.observ.model.Fmt
import com.kimbar.observ.model.Item
import com.kimbar.observ.model.ItemType
import com.kimbar.observ.model.Moment
import com.kimbar.observ.model.PHOTO_ADJUSTMENT_NS
import com.kimbar.observ.model.Sync
import com.kimbar.observ.model.TestOps
import com.kimbar.observ.model.TestTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemScreen(app: AppState, test: TestHandle, itemId: String, onBack: () -> Unit, onSyncs: () -> Unit) {
    val item = test.data.items.firstOrNull { it.id == itemId }
    if (item == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val tt = TestTime(test.data)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${item.type.name} ${tt.label(item.moment, app.format)}", fontFamily = FontFamily.Monospace) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    FormatSwitch(app)
                    TextButton(onClick = { test.update { TestOps.setItemActive(it, item.id, !item.active) } }) {
                        Text(if (item.active) "Deactivate" else "Activate")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding().padding(horizontal = 12.dp)) {
            Text(
                "local ${Fmt.localFull(item.moment.epochNs)}" +
                    (item.end?.let { " · %.1f s".format((it.counterNs - item.moment.counterNs) / 1e9) } ?: "") +
                    (if (item.file.isNotEmpty()) " · ${item.file}" else ""),
                style = MaterialTheme.typography.bodySmall,
            )
            if (!item.active) {
                Text("This item is inactive. Activate it to use it.", modifier = Modifier.padding(vertical = 16.dp))
                return@Column
            }
            when (item.type) {
                ItemType.NOTE -> NoteEditor(test, item)
                ItemType.MARK -> SyncSection(app, test, item, tt, item.moment, frame = null, onSyncs = onSyncs, modifier = Modifier.weight(1f))
                ItemType.PHOTO -> {
                    val file = test.file(item.file)
                    val bmp by produceState<Bitmap?>(null, file) { value = withContext(Dispatchers.IO) { loadOrientedBitmap(file) } }
                    MediaBox(Modifier.weight(1f)) { bmp?.let { ZoomableImage(it) } }
                    SyncSection(app, test, item, tt, item.moment, frame = null, onSyncs = onSyncs)
                }
                ItemType.VIDEO -> VideoView(app, test, item, tt, onSyncs, Modifier.weight(1f))
                ItemType.AUDIO -> AudioPlayer(test.file(item.file))
            }
        }
    }
}

@Composable
private fun MediaBox(modifier: Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun NoteEditor(test: TestHandle, item: Item) {
    var text by rememberSaveable(item.id) { mutableStateOf(item.text) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth())
        Button(
            enabled = text.trim() != item.text && text.isNotBlank(),
            onClick = { test.update { TestOps.setNoteText(it, item.id, text.trim()) } },
        ) { Text("Save") }
    }
}

private data class Frame(val index: Int, val offsetNs: Long)

@Composable
private fun VideoView(app: AppState, test: TestHandle, item: Item, tt: TestTime, onSyncs: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val file = test.file(item.file)
    val frames by produceState<VideoFrames?>(null, file) {
        value = withContext(Dispatchers.IO) { runCatching { VideoFrames(file) }.getOrNull() }
    }
    DisposableEffect(frames) { onDispose { frames?.close() } }
    var index by rememberSaveable(item.id) { mutableIntStateOf(0) }
    val f = frames
    val bmp by produceState<Bitmap?>(null, f, index) {
        value = f?.let { withContext(Dispatchers.IO) { it.frame(index.coerceIn(0, it.frameTimesUs.size - 1)) } }
    }
    MediaBox(modifier) {
        when {
            f == null -> Text("Loading frames…", color = Color.White)
            bmp != null -> ZoomableImage(bmp!!)
        }
    }
    if (f != null && f.frameTimesUs.isNotEmpty()) {
        val n = f.frameTimesUs.size
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { if (index > 0) index-- }) { Text("◀") }
            Slider(
                value = index.toFloat(),
                onValueChange = { index = it.toInt().coerceIn(0, n - 1) },
                valueRange = 0f..(n - 1).coerceAtLeast(1).toFloat(),
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { if (index < n - 1) index++ }) { Text("▶") }
        }
        val frame = Frame(index, f.offsetNanos(index))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Frame ${index + 1}/$n · ${tt.label(item.moment.plus(frame.offsetNs), app.format)}",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { openInPlayer(context, file) }) { Text("Open in player") }
        }
        SyncSection(app, test, item, tt, item.moment.plus(frame.offsetNs), frame, onSyncs)
    }
}

@Composable
private fun AudioPlayer(file: File) {
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(Unit) { onDispose { player?.release() } }
    Button(onClick = {
        val p = player
        if (p != null) {
            p.release()
            player = null
        } else {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { release(); player = null }
                prepare()
                start()
            }
        }
    }, modifier = Modifier.padding(top = 12.dp)) { Text(if (player != null) "Stop" else "Play") }
}

/**
 * Add a sync from this item (or video frame), and list the syncs it already supplies
 * (DESIGN.md 6.3).
 */
@Composable
private fun SyncSection(
    app: AppState,
    test: TestHandle,
    item: Item,
    tt: TestTime,
    moment: Moment,
    frame: Frame?,
    onSyncs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val adjustment = if (item.type == ItemType.PHOTO) PHOTO_ADJUSTMENT_NS else 0L
    var reading by remember(item.id, frame?.index, tt.averageOffsetNs == null) {
        mutableIntStateOf(tt.suggestedReadingS(moment, adjustment))
    }
    val existing = test.data.syncs.filter { it.itemId == item.id }
    Column(modifier.verticalScroll(rememberScrollState()).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider()
        Text(
            when (item.type) {
                ItemType.MARK -> "Reading of the external clock when MARK was pressed:"
                ItemType.PHOTO -> "Reading shown in the photo (+0.5 s is added automatically):"
                else -> "Reading on this frame (pick the first frame where the last digit changed):"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ReadingWheels(reading, app.format) { reading = it }
            Column {
                val t = tt.testTimeNs(moment)
                Text(
                    if (t == null) "first sync" else "delta ${Fmt.delta(reading * 1_000_000_000L + adjustment - t)}",
                    fontFamily = FontFamily.Monospace,
                )
                Button(onClick = {
                    test.update {
                        TestOps.addSync(
                            it,
                            Sync(TestStore.newId(), item.id, reading, frame = frame?.index, frameOffsetNs = frame?.offsetNs ?: 0),
                        )
                    }
                }) { Text("Add sync") }
            }
        }
        if (existing.isNotEmpty()) {
            Text("Syncs from this ${item.type.name.lowercase()}:", style = MaterialTheme.typography.labelLarge)
            existing.forEach { s -> SyncLine(s, tt, app, Modifier.clickable(onClick = onSyncs)) }
        }
    }
}

@Composable
fun SyncLine(s: Sync, tt: TestTime, app: AppState, modifier: Modifier = Modifier) {
    val counts = tt.counts(s)
    Row(modifier.fillMaxWidth().padding(vertical = 2.dp).inactiveStyle(counts)) {
        Text(Fmt.reading(s.readingS, app.format), fontFamily = FontFamily.Monospace, modifier = Modifier.padding(end = 12.dp))
        s.frame?.let { Text("frame ${it + 1}", modifier = Modifier.padding(end = 12.dp)) }
        Text(tt.deltaNs(s)?.let { Fmt.delta(it) } ?: "", fontFamily = FontFamily.Monospace)
        if (!s.active) Text("  inactive")
    }
}

private fun openInPlayer(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "video/mp4")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(view) }.onFailure { toast(context, "No video player found") }
}
