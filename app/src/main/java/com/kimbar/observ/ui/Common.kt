package com.kimbar.observ.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.camera.view.PreviewView
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kimbar.observ.media.CameraController
import com.kimbar.observ.time.TimeKeeper

/** App-wide state shared by all screens. */
class AppState(context: Context) {
    val time = TimeKeeper(context)

    /** Bumped on every sync so composables that show corrected time recompose. */
    var syncRevision by mutableIntStateOf(0)
}

/**
 * Calls [onDown] with the elapsedRealtimeNanos of the finger touching the screen, taken from
 * the touch event's own timestamp rather than from when the code happens to run.
 */
fun Modifier.onTouchDownElapsed(enabled: Boolean = true, onDown: (Long) -> Unit): Modifier =
    if (!enabled) this else pointerInput(onDown) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val ageMs = SystemClock.uptimeMillis() - down.uptimeMillis
            onDown(SystemClock.elapsedRealtimeNanos() - ageMs * 1_000_000L)
        }
    }

@Composable
fun LiveClock(app: AppState, modifier: Modifier = Modifier, fontSize: Int = 40) {
    var now by remember { mutableLongStateOf(app.time.nowEpochNanos()) }
    LaunchedEffect(app.syncRevision) {
        while (true) {
            withFrameNanos { }
            now = app.time.nowEpochNanos()
        }
    }
    Text(
        TimeKeeper.formatClock(now),
        modifier = modifier,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = fontSize.sp,
    )
}

@Composable
fun SyncStatus(app: AppState, modifier: Modifier = Modifier) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(10_000)
            tick++
        }
    }
    // Reading these states makes the status recompose on sync and every 10 s.
    if (app.syncRevision < 0 || tick < 0) return
    val t = app.time
    val sync = t.current
    Column(modifier) {
        if (sync == null) {
            Text("Not synced: using the phone clock", color = Color(0xFFD84315), style = MaterialTheme.typography.bodySmall)
        } else {
            val ageMin = (t.nowElapsed() - sync.atElapsedNanos) / 60_000_000_000L
            Text(
                "Synced by ${sync.method}, ${ageMin} min ago · vs phone clock ${TimeKeeper.formatOffset(t.offsetFromSystemNanos())}",
                style = MaterialTheme.typography.bodySmall,
            )
            t.driftPpm()?.let {
                Text(
                    "Phone clock drift ≈ %.1f ppm (%.0f ms per hour)".format(it, it * 3.6),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
fun CameraPreview(controller: CameraController, modifier: Modifier = Modifier) {
    val owner = LocalLifecycleOwner.current
    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).also { view ->
                controller.bind(owner, view) { toast(ctx, it) }
            }
        },
        modifier = modifier,
    )
}

fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

fun shareZip(context: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share session"))
}
