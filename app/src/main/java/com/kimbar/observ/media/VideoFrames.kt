package com.kimbar.observ.media

import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File

/** Frame-accurate access to a recorded video, for reading a filmed clock frame by frame. */
class VideoFrames(file: File) : AutoCloseable {

    /** Presentation times of every video frame, in microseconds, sorted. */
    val frameTimesUs: List<Long>
    private val retriever = MediaMetadataRetriever().apply { setDataSource(file.absolutePath) }

    init {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.absolutePath)
            val track = (0 until ex.trackCount).first {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            ex.selectTrack(track)
            val times = ArrayList<Long>()
            while (true) {
                val t = ex.sampleTime
                if (t < 0) break
                times += t
                ex.advance()
            }
            frameTimesUs = times.sorted()
        } finally {
            ex.release()
        }
    }

    /** Offset of frame [index] from the first frame, in nanoseconds. */
    fun offsetNanos(index: Int): Long = (frameTimesUs[index] - frameTimesUs.first()) * 1000

    fun frame(index: Int): Bitmap? =
        retriever.getFrameAtTime(frameTimesUs[index], MediaMetadataRetriever.OPTION_CLOSEST)

    override fun close() {
        retriever.release()
    }
}
