package com.kimbar.observ.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object Exporter {

    /** Already-compressed formats are stored as-is; deflating them only costs time. */
    private val storedExtensions = setOf("jpg", "jpeg", "mp4", "m4a")

    /**
     * Zips the session folder into Downloads/observ/<session>.zip and returns its content Uri,
     * which can be handed straight to a share intent.
     */
    fun exportToDownloads(context: Context, session: Session, onProgress: (Float) -> Unit = {}): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "${session.name}.zip")
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/observ")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create file in Downloads")
        try {
            resolver.openOutputStream(uri)!!.use { zip(session.dir, it, onProgress) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    private fun zip(dir: File, out: OutputStream, onProgress: (Float) -> Unit) {
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
        val total = files.sumOf { it.length() }.coerceAtLeast(1)
        var done = 0L
        val buf = ByteArray(256 * 1024)
        ZipOutputStream(out.buffered()).use { zos ->
            for (f in files) {
                val entry = ZipEntry("${dir.name}/${f.name}").apply { time = f.lastModified() }
                if (f.extension.lowercase() in storedExtensions) {
                    entry.method = ZipEntry.STORED
                    entry.size = f.length()
                    entry.compressedSize = f.length()
                    entry.crc = crc(f, buf)
                }
                zos.putNextEntry(entry)
                FileInputStream(f).use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        zos.write(buf, 0, n)
                        done += n
                        onProgress(done.toFloat() / total)
                    }
                }
                zos.closeEntry()
            }
        }
    }

    private fun crc(f: File, buf: ByteArray): Long {
        val c = CRC32()
        FileInputStream(f).use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                c.update(buf, 0, n)
            }
        }
        return c.value
    }
}
