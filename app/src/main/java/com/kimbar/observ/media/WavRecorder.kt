package com.kimbar.observ.media

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Records a mono 16-bit WAV file and works out when its first sample was actually captured.
 *
 * Uses [AudioRecord.getTimestamp] in the BOOTTIME base (same clock as elapsedRealtimeNanos),
 * which maps a frame position to the instant it was captured, so the start time is not
 * affected by the latency of starting the recorder. The end time follows from the sample count.
 */
class WavRecorder(private val file: File, private val sampleRate: Int = 48_000) {

    data class Result(val file: File, val startElapsedNanos: Long, val endElapsedNanos: Long, val precise: Boolean)

    @Volatile private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private var frames = 0L
    private var fallbackStart = 0L
    private val estimates = ArrayList<Long>()
    private var error: Exception? = null

    /** Rough start time, available immediately for the UI. */
    val approxStartElapsedNanos: Long get() = fallbackStart

    @SuppressLint("MissingPermission")
    fun start() {
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufBytes = maxOf(minBuf, sampleRate / 5 * 2) // ~200 ms
        val r = AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes * 2,
        )
        check(r.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
        record = r
        val raf = RandomAccessFile(file, "rw")
        raf.setLength(0)
        raf.write(ByteArray(44)) // header written on stop
        fallbackStart = SystemClock.elapsedRealtimeNanos()
        r.startRecording()
        running = true
        thread = Thread({ loop(r, raf, bufBytes) }, "wav-recorder").apply { start() }
    }

    private fun loop(r: AudioRecord, raf: RandomAccessFile, bufBytes: Int) {
        val buf = ByteArray(bufBytes)
        val ts = AudioTimestamp()
        try {
            while (running) {
                val n = r.read(buf, 0, buf.size)
                if (n < 0) throw IllegalStateException("AudioRecord.read error $n")
                if (n == 0) continue
                raf.write(buf, 0, n)
                frames += n / 2
                if (estimates.size < 20 &&
                    r.getTimestamp(ts, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS
                ) {
                    // Instant of frame 0 = capture time of a known frame minus its offset.
                    estimates += ts.nanoTime - ts.framePosition * 1_000_000_000L / sampleRate
                }
            }
        } catch (e: Exception) {
            error = e
        } finally {
            r.stop()
            r.release()
            writeHeader(raf, frames)
            raf.close()
        }
    }

    fun stop(): Result {
        running = false
        thread?.join()
        error?.let { throw it }
        val precise = estimates.isNotEmpty()
        val start = if (precise) estimates.sorted()[estimates.size / 2] else fallbackStart
        val end = start + frames * 1_000_000_000L / sampleRate
        return Result(file, start, end, precise)
    }

    private fun writeHeader(raf: RandomAccessFile, frames: Long) {
        val dataBytes = (frames * 2).toInt()
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(dataBytes)
        raf.seek(0)
        raf.write(b.array())
    }
}
