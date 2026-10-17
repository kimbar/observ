package com.kimbar.observ.media

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.os.SystemClock
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
import java.io.File
import kotlin.math.abs
import kotlin.math.atan

/**
 * Wraps CameraX: preview, still capture and video recording, with timing, plus the
 * front/back and normal/wide lens switches (DESIGN.md 6.2).
 *
 * Photo time is the sensor timestamp of the frame (start of exposure), not the button press.
 * Video start is estimated from recorder status events as (event time - recorded duration),
 * taking the earliest estimate; it can be late by the encoder latency (tens of ms).
 */
class CameraController(private val context: Context) {

    data class Photo(val file: File, val exposureCounterNs: Long)
    data class Video(val file: File, val startCounterNs: Long, val durationNs: Long, val error: String?)

    /** How "wide" is reached on this phone, if at all. */
    private sealed interface WideMode {
        data class Zoom(val ratio: Float) : WideMode
        data class SeparateCamera(val info: CameraInfo) : WideMode
    }

    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private var provider: ProcessCameraProvider? = null
    private var owner: LifecycleOwner? = null
    private var camera: Camera? = null
    private var wideMode: WideMode? = null
    private val preview = Preview.Builder().build()
    private val imageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .build()
    private val recorder = Recorder.Builder()
        .setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
        .build()
    private val videoCapture = VideoCapture.withOutput(recorder)
    private var recording: Recording? = null

    var front by mutableStateOf(false)
        private set
    var wide by mutableStateOf(false)
        private set
    var wideAvailable by mutableStateOf(false)
        private set
    var isRecording by mutableStateOf(false)
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
        this.owner = owner
        preview.surfaceProvider = view.surfaceProvider
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                provider = future.get()
                rebind()
                detectWide()
                orientationListener.enable()
            } catch (e: Exception) {
                onError("Camera unavailable: ${e.message}")
            }
        }, mainExecutor)
    }

    private fun rebind() {
        val p = provider ?: return
        val o = owner ?: return
        p.unbindAll()
        val mode = wideMode
        val selector = when {
            front -> CameraSelector.DEFAULT_FRONT_CAMERA
            wide && mode is WideMode.SeparateCamera ->
                CameraSelector.Builder().addCameraFilter { list -> list.filter { it == mode.info } }.build()
            else -> CameraSelector.DEFAULT_BACK_CAMERA
        }
        val cam = p.bindToLifecycle(o, selector, preview, imageCapture, videoCapture)
        camera = cam
        val ratio = if (!front && wide && mode is WideMode.Zoom) mode.ratio else 1f
        cam.cameraControl.setZoomRatio(ratio)
    }

    /**
     * Wide is either a zoom ratio below 1 on the main back camera (logical multi-camera, most
     * current phones) or a separate back camera with a clearly wider field of view.
     */
    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun detectWide() {
        val p = provider ?: return
        val min = camera?.takeIf { !front }?.cameraInfo?.zoomState?.value?.minZoomRatio
        wideMode = if (min != null && min < 0.95f) {
            WideMode.Zoom(min)
        } else {
            runCatching {
                val backs = p.availableCameraInfos.filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }
                val main = CameraSelector.DEFAULT_BACK_CAMERA.filter(backs).firstOrNull()
                fun fov(info: CameraInfo): Double? {
                    val c = Camera2CameraInfo.from(info)
                    val focal = c.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()
                    val size = c.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    if (focal == null || size == null || focal <= 0f) return null
                    return 2 * atan(size.width / (2.0 * focal))
                }
                val mainFov = main?.let { fov(it) } ?: return@runCatching null
                backs.filter { it != main }
                    .mapNotNull { info -> fov(info)?.let { info to it } }
                    .filter { it.second > mainFov * 1.2 }
                    .maxByOrNull { it.second }
                    ?.let { WideMode.SeparateCamera(it.first) }
            }.getOrNull()
        }
        wideAvailable = wideMode != null
    }

    fun toggleFront() {
        if (recording != null) return
        front = !front
        rebind()
    }

    fun toggleWide() {
        if (recording != null || front || wideMode == null) return
        wide = !wide
        rebind()
    }

    fun release() {
        orientationListener.disable()
        recording?.stop()
        provider?.unbindAll()
    }

    fun takePhoto(file: File, onDone: (Result<Photo>) -> Unit) {
        imageCapture.takePicture(mainExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val received = SystemClock.elapsedRealtimeNanos()
                onDone(runCatching {
                    image.use {
                        val exposure = sensorToElapsed(it.imageInfo.timestamp, received)
                        writeJpeg(it, file)
                        Photo(file, exposure)
                    }
                })
            }

            override fun onError(exception: ImageCaptureException) {
                onDone(Result.failure(exception))
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun startVideo(file: File, onDone: (Video) -> Unit) {
        check(recording == null) { "Already recording" }
        var bestStart = Long.MAX_VALUE
        val audioOk = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val pending = recorder.prepareRecording(context, FileOutputOptions.Builder(file).build())
            .apply { if (audioOk) withAudioEnabled() }
        recording = pending.start(mainExecutor) { event ->
            val now = SystemClock.elapsedRealtimeNanos()
            when (event) {
                is VideoRecordEvent.Status -> {
                    val d = event.recordingStats.recordedDurationNanos
                    if (d > 0) bestStart = minOf(bestStart, now - d)
                }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    isRecording = false
                    val d = event.recordingStats.recordedDurationNanos
                    val start = if (bestStart != Long.MAX_VALUE) bestStart else now - d
                    val err = if (event.hasError()) "error ${event.error}: ${event.cause?.message ?: ""}" else null
                    onDone(Video(file, start, d, err))
                }
                else -> Unit
            }
        }
        isRecording = true
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
