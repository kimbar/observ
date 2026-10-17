package com.kimbar.observ.data

import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kimbar.observ.model.Report
import com.kimbar.observ.model.TestData
import com.kimbar.observ.model.TestOps
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.format.DateTimeFormatter
import java.util.UUID

/** One Test on disk: its folder, and its current data as observable Compose state. */
class TestHandle(val dir: File, initial: TestData, private val store: TestStore) {
    var data by mutableStateOf(initial)
        private set

    /** Applies a change and saves test.json, events.txt and syncs.txt. */
    fun update(change: (TestData) -> TestData) {
        data = change(data)
        store.save(this)
    }

    fun file(name: String) = File(dir, name)

    fun newMediaFile(prefix: String, ext: String, epochNs: Long): File {
        val stamp = fileStamp.format(java.time.Instant.ofEpochMilli(epochNs / 1_000_000).atZone(java.time.ZoneId.systemDefault()))
        var f = File(dir, "${prefix}_$stamp.$ext")
        var n = 2
        while (f.exists()) f = File(dir, "${prefix}_${stamp}_${n++}.$ext")
        return f
    }

    companion object {
        private val fileStamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")
    }
}

/** All Tests, each a folder under the app's external files dir (DESIGN.md 7). */
class TestStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val root = File(context.getExternalFilesDir(null) ?: context.filesDir, "tests").apply { mkdirs() }
    private val device = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}"

    var tests by mutableStateOf(load())
        private set

    private fun load(): List<TestHandle> =
        root.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
            runCatching {
                TestHandle(dir, json.decodeFromString(TestData.serializer(), File(dir, DATA).readText()), this)
            }.getOrNull()
        }?.sortedByDescending { it.data.createdEpochNs } ?: emptyList()

    fun find(dirName: String): TestHandle? = tests.firstOrNull { it.dir.name == dirName }

    fun nameTaken(name: String): Boolean {
        val n = TestOps.normalizeName(name)
        return tests.any { TestOps.normalizeName(it.data.name) == n }
    }

    fun create(name: String, nowEpochNs: Long): TestHandle {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Name required" }
        require(!nameTaken(clean)) { "A test with this name already exists" }
        val base = TestOps.safeFileName(clean)
        var dir = File(root, base)
        var n = 2
        while (dir.exists()) dir = File(root, "${base}_${n++}")
        dir.mkdirs()
        val h = TestHandle(dir, TestData(clean, nowEpochNs), this)
        save(h)
        tests = listOf(h) + tests
        return h
    }

    fun delete(h: TestHandle) {
        require(TestOps.canDelete(h.data)) { "This test cannot be deleted yet" }
        h.dir.deleteRecursively()
        tests = tests - h
    }

    fun save(h: TestHandle) {
        val t = h.data
        writeAtomic(File(h.dir, DATA), json.encodeToString(TestData.serializer(), t))
        writeAtomic(File(h.dir, EVENTS), Report.events(t, device))
        writeAtomic(File(h.dir, SYNCS), Report.syncs(t, device))
    }

    private fun writeAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        tmp.writeText(text)
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        const val DATA = "test.json"
        const val EVENTS = "events.txt"
        const val SYNCS = "syncs.txt"

        fun newId(): String = UUID.randomUUID().toString()
    }
}
