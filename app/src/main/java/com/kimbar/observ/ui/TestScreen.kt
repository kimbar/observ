package com.kimbar.observ.ui

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kimbar.observ.data.TestHandle
import com.kimbar.observ.data.TestStore
import com.kimbar.observ.media.CameraController
import com.kimbar.observ.media.WavRecorder
import com.kimbar.observ.model.Fmt
import com.kimbar.observ.model.Item
import com.kimbar.observ.model.ItemType
import com.kimbar.observ.model.Moment
import com.kimbar.observ.model.TestOps
import com.kimbar.observ.model.TestTime

private val RecordRed = Color(0xFFC62828)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestScreen(app: AppState, test: TestHandle, onBack: () -> Unit, onOpenItem: (Item) -> Unit, onSyncs: () -> Unit) {
    val context = LocalContext.current
    val clock = app.clock
    val tt = TestTime(test.data)
    var cameraOpen by rememberSaveable { mutableStateOf(false) }
    var audio by remember { mutableStateOf<WavRecorder?>(null) }
    var videoStart by remember { mutableStateOf<Long?>(null) }
    var noteText by rememberSaveable { mutableStateOf("") }
    var notePin by remember { mutableStateOf<Moment?>(null) }
    var notePinFromMark by remember { mutableStateOf(false) }
    var toActivate by remember { mutableStateOf<Item?>(null) }
    val camera = remember { CameraController(context) }
    val recording = audio != null || camera.isRecording

    // Re-evaluated every second so the 9:00:00 limit kicks in without interaction.
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            tick++
        }
    }
    val canAdd = tick >= 0 && tt.canAddAt(clock.now())

    fun add(item: Item) = test.update { TestOps.addItem(it, item) }

    fun guardAdd(): Boolean {
        if (!TestTime(test.data).canAddAt(clock.now())) {
            toast(context, "Test time is past 9:00:00: no new items")
            return false
        }
        return true
    }

    DisposableEffect(Unit) {
        val window = (context as Activity).window
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            audio?.let { a -> runCatching { a.stop() } }
            camera.release()
        }
    }
    BackHandler(enabled = recording) { toast(context, "Stop recording first") }

    fun mark(counter: Long) {
        if (!guardAdd()) return
        val m = clock.at(counter)
        add(Item(TestStore.newId(), ItemType.MARK, m))
        if (noteText.isEmpty()) {
            notePin = m
            notePinFromMark = true
        }
    }

    fun saveNote() {
        val text = noteText.trim()
        if (text.isEmpty() || !guardAdd()) return
        add(Item(TestStore.newId(), ItemType.NOTE, notePin ?: clock.now(), text = text))
        noteText = ""
        notePin = null
        notePinFromMark = false
    }

    fun toggleAudio() {
        val a = audio
        if (a == null) {
            if (!guardAdd()) return
            val f = test.newMediaFile("audio", "wav", clock.now().epochNs)
            val r = WavRecorder(f)
            try {
                r.start()
                audio = r
            } catch (e: Exception) {
                toast(context, "Audio failed: ${e.message}")
            }
        } else {
            audio = null
            try {
                val res = a.stop()
                add(
                    Item(
                        TestStore.newId(), ItemType.AUDIO, clock.at(res.startElapsedNanos),
                        end = clock.at(res.endElapsedNanos), file = res.file.name,
                    )
                )
            } catch (e: Exception) {
                toast(context, "Audio failed: ${e.message}")
            }
        }
    }

    fun toggleVideo() {
        if (camera.isRecording) {
            camera.stopVideo()
            return
        }
        if (!guardAdd()) return
        val f = test.newMediaFile("video", "mp4", clock.now().epochNs)
        try {
            videoStart = clock.counter()
            camera.startVideo(f) { v ->
                videoStart = null
                if (v.error != null) toast(context, "Video: ${v.error}")
                if (v.file.exists() && v.file.length() > 0) {
                    val start = clock.at(v.startCounterNs)
                    add(Item(TestStore.newId(), ItemType.VIDEO, start, end = start.plus(v.durationNs), file = v.file.name))
                }
            }
        } catch (e: Exception) {
            videoStart = null
            toast(context, "Video failed: ${e.message}")
        }
    }

    fun takePhoto() {
        if (!guardAdd()) return
        val f = test.newMediaFile("photo", "jpg", clock.now().epochNs)
        camera.takePhoto(f) { r ->
            r.onSuccess { p -> add(Item(TestStore.newId(), ItemType.PHOTO, clock.at(p.exposureCounterNs), file = p.file.name)) }
                .onFailure { toast(context, "Photo failed: ${it.message}") }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(test.data.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !recording) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = { FormatSwitch(app) },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TestClock(app, tt, Modifier.weight(1f))
                FilledTonalButton(onClick = onSyncs) {
                    val n = tt.activeSyncs.size
                    Text(if (n == 0) "Syncs" else "Syncs $n · ±${Fmt.delta(tt.spreadNs ?: 0).drop(1)}")
                }
            }
            if (test.data.bootBases.size > 1) Text(
                "The phone rebooted during this test; later times are bridged via the phone clock.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            if (!canAdd) Text(
                "Test time is past 9:00:00: no new items can be added.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))

            OutlinedButton(
                onClick = { cameraOpen = !cameraOpen },
                enabled = if (cameraOpen) !camera.isRecording else audio == null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (cameraOpen) "Close camera" else "Camera") }
            Spacer(Modifier.height(6.dp))

            if (cameraOpen) {
                DisposableEffect(Unit) { onDispose { camera.release() } }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1.2f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black),
                ) {
                    CameraPreview(camera, Modifier.fillMaxSize())
                    Row(Modifier.align(Alignment.TopEnd)) {
                        if (camera.wideAvailable && !camera.front) TextButton(
                            onClick = { camera.toggleWide() },
                            enabled = !camera.isRecording,
                        ) { Text(if (camera.wide) "WIDE" else "NORMAL", color = Color.White) }
                        IconButton(onClick = { camera.toggleFront() }, enabled = !camera.isRecording) {
                            Icon(Icons.Filled.Cameraswitch, "Front / back", tint = Color.White)
                        }
                    }
                    videoStart?.let { RecordingBadge("VIDEO", it, app, Modifier.align(Alignment.TopStart).padding(8.dp)) }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TouchButton("PHOTO", Modifier.weight(1f), enabled = canAdd) { takePhoto() }
                    RecordButton(
                        if (camera.isRecording) "STOP VIDEO" else "VIDEO", camera.isRecording,
                        enabled = canAdd || camera.isRecording, modifier = Modifier.weight(1f),
                    ) { toggleVideo() }
                    TouchButton("MARK", Modifier.weight(1f), enabled = canAdd) { mark(it) }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RecordButton(
                        if (audio != null) "STOP AUDIO" else "AUDIO", audio != null,
                        enabled = canAdd || audio != null, modifier = Modifier.weight(1f),
                    ) { toggleAudio() }
                    TouchButton("MARK", Modifier.weight(1f), enabled = canAdd) { mark(it) }
                }
                audio?.let { RecordingBadge("AUDIO", it.approxStartElapsedNanos, app, Modifier.padding(top = 6.dp)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = noteText,
                        onValueChange = {
                            if (noteText.isEmpty() && it.isNotEmpty() && notePin == null) notePin = clock.now()
                            noteText = it
                            if (it.isEmpty() && !notePinFromMark) notePin = null
                        },
                        enabled = canAdd,
                        label = {
                            Text(notePin?.let { "Note @ ${tt.label(it, app.format)}" + if (notePinFromMark) " (mark)" else "" } ?: "Note")
                        },
                        modifier = Modifier.weight(1f),
                        maxLines = 4,
                    )
                    IconButton(onClick = { saveNote() }, enabled = canAdd && noteText.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Save note")
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            val syncCounts = test.data.syncs.groupingBy { it.itemId }.eachCount()
            LazyColumn(Modifier.weight(1f)) {
                items(test.data.items.sortedByDescending { tt.timeline(it.moment) }, key = { it.id }) { item ->
                    ItemRow(item, tt, app, syncCounts[item.id] ?: 0) {
                        if (item.active) onOpenItem(item) else toActivate = item
                    }
                }
            }
        }
    }

    toActivate?.let { item ->
        AlertDialog(
            onDismissRequest = { toActivate = null },
            title = { Text("Activate ${item.type.name.lowercase()}?") },
            text = { Text("Inactive items must be activated before they can be used.") },
            confirmButton = {
                TextButton(onClick = {
                    test.update { TestOps.setItemActive(it, item.id, true) }
                    toActivate = null
                }) { Text("Activate") }
            },
            dismissButton = { TextButton(onClick = { toActivate = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun ItemRow(item: Item, tt: TestTime, app: AppState, syncCount: Int, onClick: () -> Unit) {
    val small = !item.active
    val style = if (small) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = if (small) 1.dp else 6.dp)
            .inactiveStyle(item.active),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(tt.label(item.moment, app.format), fontFamily = FontFamily.Monospace, style = style, modifier = Modifier.width(if (small) 72.dp else 96.dp))
        Text(item.type.name, style = style, modifier = Modifier.width(if (small) 44.dp else 60.dp))
        val detail = when (item.type) {
            ItemType.NOTE -> item.text.replace('\n', ' ')
            ItemType.VIDEO, ItemType.AUDIO -> item.end?.let { "%.1f s".format((it.counterNs - item.moment.counterNs) / 1e9) } ?: ""
            else -> ""
        }
        Text(detail, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (syncCount > 0) Text("⟲$syncCount", style = style, color = MaterialTheme.colorScheme.primary)
        if (!item.active) Text(" inactive", style = style)
    }
}

@Composable
private fun RecordButton(label: String, active: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(56.dp),
        colors = if (active) ButtonDefaults.buttonColors(containerColor = RecordRed) else ButtonDefaults.buttonColors(),
    ) { Text(label, maxLines = 1) }
}

@Composable
private fun RecordingBadge(label: String, startCounter: Long, app: AppState, modifier: Modifier) {
    var now by remember { mutableLongStateOf(app.clock.counter()) }
    LaunchedEffect(startCounter) {
        while (true) {
            withFrameNanos { }
            now = app.clock.counter()
        }
    }
    val s = ((now - startCounter) / 1_000_000_000L).coerceAtLeast(0)
    Surface(color = RecordRed, shape = RoundedCornerShape(4.dp), modifier = modifier) {
        Text(
            "● $label %d:%02d".format(s / 60, s % 60),
            color = Color.White,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
