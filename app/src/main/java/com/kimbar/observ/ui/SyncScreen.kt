package com.kimbar.observ.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.exifinterface.media.ExifInterface
import com.kimbar.observ.data.Event
import com.kimbar.observ.data.Session
import com.kimbar.observ.media.CameraController
import com.kimbar.observ.media.VideoFrames
import com.kimbar.observ.time.Sntp
import com.kimbar.observ.time.TimeInput
import com.kimbar.observ.time.TimeKeeper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A moment on the phone's clock waiting for the user to say what the reference clock showed. */
private data class Candidate(val elapsedNanos: Long, val method: String, val file: String = "")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(app: AppState, session: Session?, onBack: () -> Unit) {
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Manual", "Photo", "Video", "NTP")

    fun applySync(c: Candidate, referenceEpochNanos: Long, extra: String = "") {
        val t = app.time
        val change = t.applySync(c.elapsedNanos, referenceEpochNanos, c.method)
        app.syncRevision++
        session?.append(
            Event(
                c.elapsedNanos, referenceEpochNanos, "SYNC", c.file,
                "method=${c.method} base_ns=${t.bootEpochNanos()} change=${TimeKeeper.formatOffset(change)} " +
                    "offset_vs_phone=${TimeKeeper.formatOffset(t.offsetFromSystemNanos())}$extra",
            )
        )
        toast(context, "Synced: clock moved ${TimeKeeper.formatOffset(change)}")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sync clock") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (app.time.isSynced) TextButton(onClick = {
                        app.time.clearSync()
                        app.syncRevision++
                    }) { Text("Reset") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            Column(Modifier.padding(horizontal = 12.dp)) {
                LiveClock(app)
                SyncStatus(app)
            }
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, label -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) }) }
            }
            Box(Modifier.weight(1f).padding(12.dp)) {
                val mediaDir = session?.dir ?: File(context.cacheDir, "sync").apply { mkdirs() }
                when (tab) {
                    0 -> ManualSync(app, ::applySync)
                    1 -> PhotoSync(app, mediaDir, ::applySync)
                    2 -> VideoSync(app, mediaDir, ::applySync)
                    3 -> NtpSync(app, ::applySync)
                }
            }
        }
    }
}

/** Text box for the reference time plus the Apply button, shared by the camera/manual methods. */
@Composable
private fun ReferenceEntry(app: AppState, candidate: Candidate?, onApply: (Candidate, Long) -> Unit) {
    var text by remember(candidate) {
        mutableStateOf(candidate?.let { TimeInput.suggestion(app.time.toEpochNanos(it.elapsedNanos)) } ?: "")
    }
    if (candidate == null) return
    val phoneView = app.time.toEpochNanos(candidate.elapsedNanos)
    val parsed = TimeInput.parse(text, phoneView)
    Column {
        Text(
            "Phone clock at that moment: ${TimeKeeper.formatClock(phoneView)}",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Reference clock showed (HH:mm:ss.SSS)") },
                singleLine = true,
                isError = parsed == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.padding(4.dp))
            Button(enabled = parsed != null, onClick = { parsed?.let { onApply(candidate, it) } }) { Text("Apply") }
        }
        if (parsed != null) Text(
            "Correction: ${TimeKeeper.formatOffset(parsed - phoneView)}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ManualSync(app: AppState, onApply: (Candidate, Long, String) -> Unit) {
    var candidate by remember { mutableStateOf<Candidate?>(null) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Watch the reference clock and tap the pad exactly when it changes to a new second " +
                "(anticipate the change rather than react to it). Then type the time it showed.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .onTouchDownElapsed { candidate = Candidate(it, "manual-tap") },
        ) {
            Box(contentAlignment = Alignment.Center) { Text("TAP", fontSize = 40.sp) }
        }
        ReferenceEntry(app, candidate) { c, ref -> onApply(c, ref, "") }
    }
}

@Composable
private fun PhotoSync(app: AppState, dir: File, onApply: (Candidate, Long, String) -> Unit) {
    val context = LocalContext.current
    val camera = remember { CameraController(context) }
    DisposableEffect(Unit) { onDispose { camera.release() } }
    var candidate by remember { mutableStateOf<Candidate?>(null) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Photograph the reference clock (a display showing milliseconds works best), " +
                "then zoom into the photo and type the time it shows.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(8.dp)).background(Color.Black)) {
            val b = bitmap
            if (b == null) CameraPreview(camera, Modifier.fillMaxSize()) else ZoomableImage(b)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (bitmap == null) {
                TouchButton("TAKE PHOTO", Modifier.weight(1f)) {
                    val f = File(dir, "sync_photo_${System.currentTimeMillis()}.jpg")
                    camera.takePhoto(f) { r ->
                        r.onSuccess { p ->
                            candidate = Candidate(p.exposureElapsedNanos, "photo", p.file.name)
                            scope.launch { bitmap = withContext(Dispatchers.IO) { loadOriented(p.file) } }
                        }.onFailure { toast(context, "Photo failed: ${it.message}") }
                    }
                }
            } else {
                OutlinedButton(onClick = { bitmap = null; candidate = null }) { Text("Retake") }
            }
        }
        ReferenceEntry(app, candidate) { c, ref -> onApply(c, ref, "") }
    }
}

@Composable
private fun VideoSync(app: AppState, dir: File, onApply: (Candidate, Long, String) -> Unit) {
    val context = LocalContext.current
    val camera = remember { CameraController(context) }
    var recording by remember { mutableStateOf(false) }
    var clip by remember { mutableStateOf<Pair<CameraController.Video, VideoFrames>?>(null) }
    var index by remember { mutableIntStateOf(0) }
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            camera.release()
            clip?.second?.close()
        }
    }
    LaunchedEffect(clip, index) {
        val c = clip ?: return@LaunchedEffect
        frame = withContext(Dispatchers.IO) { c.second.frame(index) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Film the reference clock for a few seconds. Then step through the frames, pick one " +
                "where the clock is readable, and type the time it shows. Video start times are " +
                "estimated, so this can be a few tens of ms off; Photo is more precise.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(8.dp)).background(Color.Black)) {
            val c = clip
            val f = frame
            if (c == null) CameraPreview(camera, Modifier.fillMaxSize()) else if (f != null) ZoomableImage(f)
        }
        val c = clip
        if (c == null) {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (recording) {
                        camera.stopVideo()
                    } else {
                        val f = File(dir, "sync_video_${System.currentTimeMillis()}.mp4")
                        try {
                            camera.startVideo(f, withAudio = false, onStarted = {}) { v ->
                                recording = false
                                if (v.error != null && !v.file.exists()) {
                                    toast(context, "Video: ${v.error}")
                                } else {
                                    runCatching { VideoFrames(v.file) }
                                        .onSuccess { frames -> index = 0; clip = v to frames }
                                        .onFailure { toast(context, "Could not read video: ${it.message}") }
                                }
                            }
                            recording = true
                        } catch (e: Exception) {
                            toast(context, "Video failed: ${e.message}")
                        }
                    }
                },
            ) { Text(if (recording) "STOP" else "RECORD") }
        } else {
            val n = c.second.frameTimesUs.size
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Frame ${index + 1}/$n at +%.3f s".format(c.second.offsetNanos(index) / 1e9),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { c.second.close(); clip = null; frame = null }) { Text("Retake") }
            }
            val cand = remember(c, index) {
                Candidate(c.first.startElapsedNanos + c.second.offsetNanos(index), "video", c.first.file.name)
            }
            ReferenceEntry(app, cand) { cd, ref -> onApply(cd, ref, " frame=${index + 1}") }
        }
    }
}

@Composable
private fun NtpSync(app: AppState, onApply: (Candidate, Long, String) -> Unit) {
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("pool.ntp.org") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Sntp.Result?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Sync to an internet time server. Accuracy is roughly half the network round trip " +
                "(usually 5–50 ms). Use this when the reference is itself an NTP-synced clock.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Server") }, singleLine = true)
        Button(enabled = !busy, onClick = {
            busy = true
            error = null
            scope.launch {
                try {
                    result = withContext(Dispatchers.IO) { Sntp.query(host.trim()) }
                } catch (e: Exception) {
                    error = e.message ?: e.toString()
                } finally {
                    busy = false
                }
            }
        }) { Text(if (busy) "Querying…" else "Query") }
        error?.let { Text("Failed: $it", color = MaterialTheme.colorScheme.error) }
        result?.let { r ->
            val phone = app.time.toEpochNanos(r.elapsedNanos)
            Text(
                "Round trip %.1f ms\nServer vs current clock: %s".format(
                    r.roundTripNanos / 1e6, TimeKeeper.formatOffset(r.serverEpochNanos - phone),
                ),
                fontFamily = FontFamily.Monospace,
            )
            Button(onClick = {
                onApply(
                    Candidate(r.elapsedNanos, "ntp:${host.trim()}"), r.serverEpochNanos,
                    " rtt_ms=%.1f".format(r.roundTripNanos / 1e6),
                )
            }) { Text("Apply") }
        }
    }
}

@Composable
private fun ZoomableImage(bitmap: Bitmap) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 12f)
        offset += pan
    }
    Image(
        bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .transformable(state)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    )
}

private fun loadOriented(file: File): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 3000) sample *= 2
    val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return null
    val degrees = when (ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (degrees == 0f) return bmp
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
}
