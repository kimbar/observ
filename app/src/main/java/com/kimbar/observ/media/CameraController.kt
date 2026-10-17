package com.kimbar.observ.media

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.os.SystemClock
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
import java.io.File
import kotlin.math.abs

/**
 * Wraps CameraX: preview, still capture and video recording, with timing.
 *
 * Photo time is the sensor timestamp of the frame (start of exposure), not the button press.
 * Video start is estimated from recorder status events as (event time - recorded duration),
 * taking the earliest estimate; it can be late by the encoder latency (typically tens of ms).
 * Video sync against a filmed clock cancels that bias for later videos.
 */
class CameraController(private val context: Context) {

    data class Photo(val file: File, val exposureElapsedNanos: Long, val pressElapsedNanos: Long)
    data class Video(val file: File, val startElapsedNanos: Long, val endElapsedNanos: Long, val error: String?)

    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private val preview = Preview.Builder().build()
    private val imageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .build()
    private val recorder = Recorder.Builder()
        .setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
        .build()
    private val videoCapture = VideoCapture.withOutput(recorder)
    private var recording: Recording? = null

    var lensFacing = CameraSelector.LENS_FACING_BACK
        private set

    private val orientationListener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            val rotation = when (orientation) {
                in 45 until 135 -> Surface.ROTATION_270
                in 135 until 225 -> Surface.ROTATION_180
                in 225 until 315 -> Surface.ROTATION_90
                else -> Surface.ROTATION_0
            }
            imageCapture.targetRotation = rotation
            if (recording == null) videoCapture.targetRotation = rotation
        }
    }

    fun bind(owner: LifecycleOwner, view: PreviewView, onError: (String) -> Unit) {
        preview.surfaceProvider = view.surfaceProvider
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                provider = future.get()
                rebind(owner)
                orientationListener.enable()
            } catch (e: Exception) {
                onError("Camera unavailable: ${e.message}")
            }
        }, mainExecutor)
    }

    private fun rebind(owner: LifecycleOwner) {
        val p = provider ?: return
        p.unbindAll()
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        camera = p.bindToLifecycle(owner, selector, preview, imageCapture, videoCapture)
    }

    fun switchLens(owner: LifecycleOwner) {
        if (recording != null) return
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT
        else CameraSelector.LENS_FACING_BACK
        rebind(owner)
    }

    fun release() {
        orientationListener.disable()
        recording?.stop()
        provider?.unbindAll()
    }

    fun takePhoto(file: File, onDone: (Result<Photo>) -> Unit) {
        val pressed = SystemClock.elapsedRealtimeNanos()
        imageCapture.takePicture(mainExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val received = SystemClock.elapsedRealtimeNanos()
                onDone(runCatching {
                    image.use {
                        val exposure = sensorToElapsed(it.imageInfo.timestamp, received)
                        writeJpeg(it, file)
                        Photo(file, exposure, pressed)
                    }
                })
            }

            override fun onError(exception: ImageCaptureException) {
                onDone(Result.failure(exception))
            }
        })
    }

    val isRecording: Boolean get() = recording != null

    @SuppressLint("MissingPermission")
    fun startVideo(file: File, withAudio: Boolean, onStarted: (approxStartElapsed: Long) -> Unit, onDone: (Video) -> Unit) {
        check(recording == null) { "Already recording" }
        var bestStart = Long.MAX_VALUE
        var started = false
        val audioOk = withAudio && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val pending = recorder.prepareRecording(context, FileOutputOptions.Builder(file).build())
            .apply { if (audioOk) withAudioEnabled() }
        recording = pending.start(mainExecutor) { event ->
            val now = SystemClock.elapsedRealtimeNanos()
            when (event) {
                is VideoRecordEvent.Status -> {
                    val d = event.recordingStats.recordedDurationNanos
                    if (d > 0) bestStart = minOf(bestStart, now - d)
                    if (!started) {
                        started = true
                        onStarted(if (bestStart != Long.MAX_VALUE) bestStart else now)
                    }
                }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    val d = event.recordingStats.recordedDurationNanos
                    val start = if (bestStart != Long.MAX_VALUE) bestStart else now - d
                    val err = if (event.hasError()) "error ${event.error}: ${event.cause?.message ?: ""}" else null
                    onDone(Video(file, start, start + d, err))
                }
                else -> Unit
            }
        }
    }

    fun stopVideo() {
        recording?.stop()
    }

    private fun writeJpeg(image: ImageProxy, file: File) {
        check(image.format == ImageFormat.JPEG) { "Unexpected image format ${image.format}" }
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        file.writeBytes(bytes)
        val exif = ExifInterface(file)
        val orientation = when (image.imageInfo.rotationDegrees) {
            90 -> ExifInterface.ORIENTATION_ROTATE_90
            180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_NORMAL
        }
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
        exif.saveAttributes()
    }

    companion object {
        /**
         * Camera sensor timestamps are in either the BOOTTIME base (= elapsedRealtimeNanos) or
         * the MONOTONIC base (= System.nanoTime), depending on the device. Pick whichever
         * interpretation lands just before the moment the image arrived.
         */
        fun sensorToElapsed(sensorTs: Long, receivedElapsed: Long): Long {
            val asRealtime = sensorTs
            val asMonotonic = sensorTs + (SystemClock.elapsedRealtimeNanos() - System.nanoTime())
            fun score(t: Long) = if (t > receivedElapsed + 5_000_000L) Long.MAX_VALUE else abs(receivedElapsed - t)
            return if (score(asRealtime) <= score(asMonotonic)) asRealtime else asMonotonic
        }
    }
}
