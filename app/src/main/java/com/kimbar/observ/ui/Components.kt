package com.kimbar.observ.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kimbar.observ.data.PhoneClock
import com.kimbar.observ.media.CameraController
import com.kimbar.observ.model.Fmt
import com.kimbar.observ.model.TestTime
import com.kimbar.observ.model.TimeFormat
import com.kimbar.observ.model.Wheels
import kotlinx.coroutines.launch
import java.io.File

/** App-wide state shared by all screens. */
class AppState(context: Context) {
    val clock = PhoneClock(context)
    val store = TestStore(context)
    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    /** The global display format switch (DESIGN.md 4). */
    var format by mutableStateOf(if (prefs.getString("format", "A") == "B") TimeFormat.B else TimeFormat.A)
        private set

    fun toggleFormat() {
        format = if (format == TimeFormat.A) TimeFormat.B else TimeFormat.A
        prefs.edit().putString("format", format.name).apply()
    }
}

@Composable
fun FormatSwitch(app: AppState) {
    OutlinedButton(onClick = { app.toggleFormat() }, contentPadding = PaddingValues(horizontal = 10.dp)) {
        Text(if (app.format == TimeFormat.A) "MMM:SS" else "H:MM:SS", fontFamily = FontFamily.Monospace)
    }
}

/** Big live test clock, or "not synced", with the local time beneath. */
@Composable
fun TestClock(app: AppState, tt: TestTime, modifier: Modifier = Modifier) {
    var counter by remember { mutableLongStateOf(app.clock.counter()) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { }
            counter = app.clock.counter()
        }
    }
    val now = app.clock.at(counter)
    val t = tt.testTimeNs(now)
    androidx.compose.foundation.layout.Column(modifier) {
        Text(
            if (t != null) Fmt.testTime(t, app.format) else "not synced",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = if (t != null) 44.sp else 32.sp,
            color = if (t != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
        )
        Text(
            "local ${Fmt.localClock(now.epochNs)}",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Test time of a moment in the current format, or a placeholder when not synced. */
fun TestTime.label(m: com.kimbar.observ.model.Moment, format: TimeFormat): String =
    testTimeNs(m)?.let { Fmt.testTime(it, format) } ?: "--"

/**
 * Swipeable digit wheels for an external clock reading, separators fixed, no tenths
 * (DESIGN.md 4). The layout follows [format]; the value is whole seconds.
 */
@Composable
fun ReadingWheels(seconds: Int, format: TimeFormat, onChange: (Int) -> Unit) {
    val maxima = Wheels.maxima(format)
    val digits = Wheels.digits(seconds, format)
    val separators = Wheels.separatorsAfter(format)
    Row(verticalAlignment = Alignment.CenterVertically) {
        maxima.forEachIndexed { i, max ->
            key(format, i) {
                DigitWheel(digits[i], max) { d ->
                    val newDigits = Wheels.digits(seconds, format).toMutableList().also { it[i] = d }
                    onChange(Wheels.seconds(newDigits, format).coerceAtMost(TestTime.MAX_READING_S))
                }
            }
            if (i in separators) Text(":", fontSize = 32.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun DigitWheel(value: Int, max: Int, onChange: (Int) -> Unit) {
    val itemHeight = 44.dp
    val itemPx = with(LocalDensity.current) { itemHeight.toPx() }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = value)
    val fling = rememberSnapFlingBehavior(lazyListState = state)
    val scope = rememberCoroutineScope()
    val currentValue by rememberUpdatedState(value)
    val currentOnChange by rememberUpdatedState(onChange)
    // External value changes (e.g. a new suggestion) move the wheel.
    LaunchedEffect(value) {
        if (!state.isScrollInProgress && centered(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, itemPx) != value) {
            state.scrollToItem(value)
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) {
                val c = centered(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, itemPx).coerceIn(0, max)
                if (c != currentValue) currentOnChange(c)
            }
        }
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.padding(horizontal = 2.dp).width(40.dp).height(itemHeight * 3),
    ) {
        Box {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(itemHeight)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            )
            LazyColumn(
                state = state,
                flingBehavior = fling,
                contentPadding = PaddingValues(vertical = itemHeight),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(max + 1) { d ->
                    Box(
                        Modifier
                            .height(itemHeight)
                            .fillMaxWidth()
                            .clickable { scope.launch { state.animateScrollToItem(d) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            d.toString(),
                            fontSize = 28.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.alpha(if (d == value) 1f else 0.4f),
                        )
                    }
                }
            }
        }
    }
}

private fun centered(index: Int, offset: Int, itemPx: Float): Int = index + if (offset > itemPx / 2) 1 else 0

/**
 * Calls [onDown] with the counter value of the finger touching the screen, taken from the
 * touch event's own timestamp rather than from when the code happens to run.
 */
fun Modifier.onTouchDown(enabled: Boolean = true, onDown: (Long) -> Unit): Modifier =
    if (!enabled) this else pointerInput(onDown) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val ageMs = SystemClock.uptimeMillis() - down.uptimeMillis
            onDown(SystemClock.elapsedRealtimeNanos() - ageMs * 1_000_000L)
        }
    }

/** A button that fires on finger-down, passing the precise touch time. */
@Composable
fun TouchButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onDown: (Long) -> Unit) {
    Surface(
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(24.dp),
        modifier = modifier.height(56.dp).onTouchDown(enabled, onDown),
    ) {
        Box(contentAlignment = Alignment.Center) { Text(label, maxLines = 1, fontWeight = FontWeight.Bold) }
    }
}

@Composable
fun CameraPreview(controller: CameraController, modifier: Modifier = Modifier) {
    val owner = LocalLifecycleOwner.current
    AndroidView(
        factory = { ctx -> PreviewView(ctx).also { view -> controller.bind(owner, view) { toast(ctx, it) } } },
        modifier = modifier,
    )
}

@Composable
fun ZoomableImage(bitmap: Bitmap, modifier: Modifier = Modifier) {
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
        modifier = modifier
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

fun loadOrientedBitmap(file: File, maxSide: Int = 3000): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
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

fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

fun shareZip(context: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share test"))
}

/** Rows of inactive things are smaller and dimmed (DESIGN.md 6.2). */
fun Modifier.inactiveStyle(active: Boolean): Modifier = if (active) this else alpha(0.45f)
