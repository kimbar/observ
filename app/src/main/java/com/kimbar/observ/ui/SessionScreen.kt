package com.kimbar.observ.ui

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.kimbar.observ.data.Event
import com.kimbar.observ.data.Exporter
import com.kimbar.observ.data.Session
import com.kimbar.observ.media.CameraController
import com.kimbar.observ.media.WavRecorder
import com.kimbar.observ.time.TimeKeeper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(app: AppState, session: Session, onBack: () -> Unit, onSync: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val time = app.time
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val camera = remember { CameraController(context) }
    val events = remember { mutableStateListOf<Event>().apply { addAll(session.readEvents()) } }
    var video by remember { mutableStateOf<Long?>(null) } // approx start, while recording
    var videoPending by remember { mutableStateOf(false) }
    var audio by remember { mutableStateOf<WavRecorder?>(null) }
    var noteText by remember { mutableStateOf("") }
    var notePinned by remember { mutableStateOf<Long?>(null) }
    var exportProgress by remember { mutableStateOf<Float?>(null) }

    fun log(elapsed: Long, type: String, file: String = "", text: String = "") {
        val e = Event(elapsed, time.toEpochNanos(elapsed), type, file, text)
        session.append(e)
        events.add(e)
    }

    DisposableEffect(Unit) {
        val window = (context as Activity).window
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val s = time.current
        log(
            time.nowElapsed(), "TIMEBASE", "",
            "base_ns=${time.bootEpochNanos()} method=${s?.method ?: "phone-clock"} " +
                "offset_vs_phone=${TimeKeeper.formatOffset(time.offsetFromSystemNanos())}",
        )
        onDispose {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            audio?.let { a -> runCatching { a.stop() } }
            camera.release()
        }
    }

    BackHandler(enabled = video != null || audio != null) { toast(context, "Stop recording first") }

    fun toggleAudio() {
        val a = audio
        if (a == null) {
            val f = session.newFile("audio", "wav", time.nowEpochNanos())
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
                val q = if (res.precise) "" else "approximate (no audio timestamp)"
                log(res.startElapsedNanos, "AUDIO_START", res.file.name, q)
                log(res.endElapsedNanos, "AUDIO_END", res.file.name,
                    "duration=%.3f s".format((res.endElapsedNanos - res.startElapsedNanos) / 1e9))
            } catch (e: Exception) {
                toast(context, "Audio failed: ${e.message}")
            }
        }
    }

    fun toggleVideo() {
        if (camera.isRecording) {
            videoPending = true
            camera.stopVideo()
            return
        }
        val f = session.newFile("video", "mp4", time.nowEpochNanos())
        try {
            camera.startVideo(
                f, withAudio = true,
                onStarted = { video = it },
                onDone = { v ->
                    video = null
                    videoPending = false
                    if (v.error != null) toast(context, "Video: ${v.error}")
                    if (v.file.exists() && v.file.length() > 0) {
                        log(v.startElapsedNanos, "VIDEO_START", v.file.name, "estimated from recorder")
                        log(v.endElapsedNanos, "VIDEO_END", v.file.name,
                            "duration=%.3f s".format((v.endElapsedNanos - v.startElapsedNanos) / 1e9))
                    }
                },
            )
            video = time.nowElapsed()
        } catch (e: Exception) {
            toast(context, "Video failed: ${e.message}")
        }
    }

    fun takePhoto() {
        val f = session.newFile("photo", "jpg", time.nowEpochNanos())
        camera.takePhoto(f) { r ->
            r.onSuccess { p ->
                log(p.exposureElapsedNanos, "PHOTO", p.file.name,
                    "press_to_exposure=%+.0f ms".format((p.exposureElapsedNanos - p.pressElapsedNanos) / 1e6))
            }.onFailure { toast(context, "Photo failed: ${it.message}") }
        }
    }

    fun mark(elapsed: Long) {
        log(elapsed, "MARK")
        notePinned = elapsed
    }

    fun addNote() {
        val text = noteText.trim()
        if (text.isEmpty()) return
        log(notePinned ?: time.nowElapsed(), "NOTE", "", text)
        noteText = ""
        notePinned = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(session.name, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = video == null && audio == null) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    TextButton(onClick = onSync, enabled = video == null && audio == null) { Text("Sync") }
                    TextButton(
                        enabled = exportProgress == null && video == null && audio == null,
                        onClick = {
                            exportProgress = 0f
                            scope.launch {
                                try {
                                    val uri = withContext(Dispatchers.IO) {
                                        Exporter.exportToDownloads(context, session) { p -> exportProgress = p }
                                    }
                                    toast(context, "Saved to Downloads/observ/${session.name}.zip")
                                    shareZip(context, uri)
                                } catch (e: Exception) {
                                    toast(context, "Export failed: ${e.message}")
                                } finally {
                                    exportProgress = null
                                }
                            }
                        },
                    ) { Text("Export") }
                },
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
            exportProgress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
            LiveClock(app)
            SyncStatus(app)
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black),
            ) {
                CameraPreview(camera, Modifier.fillMaxSize())
                IconButton(
                    onClick = { camera.switchLens(lifecycleOwner) },
                    enabled = video == null,
                    modifier = Modifier.align(Alignment.TopEnd),
                ) { Icon(Icons.Filled.Cameraswitch, "Switch camera", tint = Color.White) }
                val recStart = video ?: audio?.approxStartElapsedNanos
                if (recStart != null) RecordingBadge(
                    if (video != null) "VIDEO" else "AUDIO", recStart, time,
                    Modifier.align(Alignment.TopStart).padding(8.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TouchButton("PHOTO", Modifier.weight(1f), enabled = !videoPending) { takePhoto() }
                ToggleButton(
                    if (video != null) "STOP VIDEO" else "VIDEO", video != null, Modifier.weight(1f),
                    enabled = audio == null && !videoPending,
                ) { toggleVideo() }
                ToggleButton(
                    if (audio != null) "STOP AUDIO" else "AUDIO", audio != null, Modifier.weight(1f),
                    enabled = video == null && !videoPending,
                ) { toggleAudio() }
                TouchButton("MARK", Modifier.weight(1f)) { mark(it) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = {
                        if (noteText.isEmpty() && it.isNotEmpty() && notePinned == null) notePinned = time.nowElapsed()
                        noteText = it
                        if (it.isEmpty()) notePinned = null
                    },
                    label = {
                        Text(notePinned?.let { "Note @ ${TimeKeeper.formatClock(time.toEpochNanos(it))}" } ?: "Note")
                    },
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                )
                IconButton(onClick = { addNote() }) { Icon(Icons.AutoMirrored.Filled.Send, "Add note") }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            LazyColumn(Modifier.weight(0.8f)) {
                items(events.sortedByDescending { it.epochNanos }.filter { it.type != "TIMEBASE" }) { e ->
                    EventRow(e)
                }
            }
        }
    }
}

@Composable
private fun EventRow(e: Event) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(
            TimeKeeper.formatClock(e.epochNanos),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.width(8.dp))
        Text(e.type, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(92.dp))
        Text(
            listOf(e.file, e.text).filter { it.isNotEmpty() }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
        )
    }
}

@Composable
private fun RecordingBadge(label: String, startElapsed: Long, time: TimeKeeper, modifier: Modifier) {
    var now by remember { mutableLongStateOf(time.nowElapsed()) }
    LaunchedEffect(startElapsed) {
        while (true) {
            withFrameNanos { }
            now = time.nowElapsed()
        }
    }
    val s = ((now - startElapsed) / 1_000_000_000L).coerceAtLeast(0)
    Surface(color = Color(0xFFC62828), shape = RoundedCornerShape(4.dp), modifier = modifier) {
        Text(
            "● $label %d:%02d".format(s / 60, s % 60),
            color = Color.White,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** A button that fires on finger-down, passing the precise touch time. */
@Composable
fun TouchButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onDown: (Long) -> Unit) {
    Surface(
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .height(48.dp)
            .onTouchDownElapsed(enabled, onDown),
    ) {
        Box(contentAlignment = Alignment.Center) { Text(label, maxLines = 1) }
    }
}

@Composable
private fun ToggleButton(label: String, active: Boolean, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled || active,
        modifier = modifier.height(48.dp),
        colors = if (active) ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)) else ButtonDefaults.buttonColors(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
    ) { Text(label, maxLines = 1) }
}
