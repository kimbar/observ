package com.kimbar.observ.data

import android.content.Context
import android.os.Build
import com.kimbar.observ.time.TimeKeeper
import java.io.File
import java.time.format.DateTimeFormatter

data class Event(
    val elapsedNanos: Long,
    val epochNanos: Long,
    val type: String,
    val file: String = "",
    val text: String = "",
)

/**
 * One observation session: a folder holding media files and `events.txt`, a tab-separated
 * log appended to (and flushed) as events happen, so nothing is lost if the app dies.
 */
class Session(val dir: File) {

    val name: String get() = dir.name
    val log: File get() = File(dir, LOG_NAME)

    fun newFile(prefix: String, ext: String, epochNanos: Long): File {
        val stamp = fileStamp.format(TimeKeeper.instant(epochNanos).atZone(TimeKeeper.zone))
        var f = File(dir, "${prefix}_$stamp.$ext")
        var n = 2
        while (f.exists()) f = File(dir, "${prefix}_${stamp}_${n++}.$ext")
        return f
    }

    @Synchronized
    fun append(e: Event) {
        val line = listOf(
            TimeKeeper.formatFull(e.epochNanos),
            TimeKeeper.formatEpochSeconds(e.epochNanos),
            e.elapsedNanos.toString(),
            e.type,
            e.file,
            e.text.replace("\t", " ").replace("\r", "").replace("\n", "\\n"),
        ).joinToString("\t")
        log.appendText(line + "\n")
    }

    fun comment(text: String) {
        log.appendText(text.lines().joinToString("\n") { "# $it" } + "\n")
    }

    fun readEvents(): List<Event> {
        if (!log.exists()) return emptyList()
        return log.readLines().mapNotNull { line ->
            if (line.startsWith("#") || line.isBlank()) return@mapNotNull null
            val c = line.split('\t')
            if (c.size < 4) return@mapNotNull null
            val epoch = c[1].toBigDecimalOrNull()?.movePointRight(9)?.toLong() ?: return@mapNotNull null
            Event(c[2].toLongOrNull() ?: 0, epoch, c[3], c.getOrElse(4) { "" }, c.getOrElse(5) { "" }.replace("\\n", "\n"))
        }
    }

    companion object {
        const val LOG_NAME = "events.txt"
        private val fileStamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")
        private val dirStamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")

        fun root(context: Context): File =
            File(context.getExternalFilesDir(null) ?: context.filesDir, "sessions").apply { mkdirs() }

        fun list(context: Context): List<Session> =
            root(context).listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }?.map { Session(it) }
                ?: emptyList()

        fun create(context: Context, time: TimeKeeper, title: String): Session {
            val now = time.nowEpochNanos()
            val base = dirStamp.format(TimeKeeper.instant(now).atZone(TimeKeeper.zone))
            val safe = title.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_')
            val dir = File(root(context), if (safe.isEmpty()) base else "${base}_$safe")
            dir.mkdirs()
            val s = Session(dir)
            s.comment(
                """
                observ session ${s.name}
                device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}
                time zone: ${TimeKeeper.zone.id}
                columns: local_time  epoch_seconds  elapsed_ns  event  file  text
                elapsed_ns = phone monotonic clock (SystemClock.elapsedRealtimeNanos) at the event.
                local_time/epoch_seconds = elapsed_ns + time base of the latest SYNC line before the event.
                To re-correct with a later sync: epoch_ns = elapsed_ns + base_ns from that SYNC line.
                """.trimIndent()
            )
            return s
        }
    }
}
