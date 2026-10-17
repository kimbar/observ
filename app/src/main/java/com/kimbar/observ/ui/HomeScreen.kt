package com.kimbar.observ.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kimbar.observ.data.Exporter
import com.kimbar.observ.data.TestHandle
import com.kimbar.observ.model.Fmt
import com.kimbar.observ.model.TestOps
import com.kimbar.observ.model.TestTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(app: AppState, onOpen: (TestHandle) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<TestHandle?>(null) }
    var exporting by remember { mutableStateOf<Float?>(null) }
    val (active, inactive) = app.store.tests.partition { it.data.active }

    fun export(h: TestHandle) {
        exporting = 0f
        scope.launch {
            try {
                val name = TestOps.exportFileName(h.data)
                val uri = withContext(Dispatchers.IO) {
                    Exporter.exportToDownloads(context, h.dir, name) { exporting = it }
                }
                h.update { TestOps.markExported(it) }
                toast(context, "Saved Downloads/observ/$name")
                shareZip(context, uri)
            } catch (e: Exception) {
                toast(context, "Export failed: ${e.message}")
            } finally {
                exporting = null
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Tests") }, actions = { FormatSwitch(app) }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("New test") },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            exporting?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
            if (app.store.tests.isEmpty()) {
                Text("No tests yet.", modifier = Modifier.padding(16.dp))
            }
            LazyColumn(Modifier.weight(1f)) {
                items(active + inactive, key = { it.dir.name }) { h ->
                    TestRow(
                        h,
                        onOpen = { onOpen(h) },
                        onExport = { export(h) },
                        onToggleActive = { h.update { TestOps.setTestActive(it, !it.active) } },
                        onDelete = { toDelete = h },
                        exportEnabled = exporting == null,
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (creating) NewTestDialog(app, onDismiss = { creating = false }) { name ->
        creating = false
        onOpen(app.store.create(name, app.clock.now().epochNs))
    }

    toDelete?.let { h ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete \"${h.data.name}\" permanently?") },
            text = { Text("All its photos, videos, audio and notes are removed from this phone. This cannot be undone. Exported zips in Downloads are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { app.store.delete(h) }.onFailure { toast(context, it.message ?: "Delete failed") }
                    toDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TestRow(
    h: TestHandle,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    onToggleActive: () -> Unit,
    onDelete: () -> Unit,
    exportEnabled: Boolean,
) {
    val t = h.data
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = t.active, onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = if (t.active) 10.dp else 4.dp)
            .inactiveStyle(t.active),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(t.name, style = if (t.active) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodySmall)
            val synced = if (TestTime(t).isSynced) "synced" else "not synced"
            val exported = when {
                t.exportCount == 0 -> "not exported"
                t.changedSinceExport -> "changed since export v.${t.exportCount}"
                else -> "exported v.${t.exportCount}"
            }
            Text(
                "${Fmt.localDate(t.createdEpochNs)} · ${t.items.size} items · $synced · $exported" +
                    if (t.active) "" else " · inactive",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Actions") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (t.active) DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; onOpen() })
            DropdownMenuItem(
                text = { Text("Export") },
                enabled = TestOps.canExport(t) && exportEnabled,
                onClick = { menu = false; onExport() },
            )
            DropdownMenuItem(
                text = { Text(if (t.active) "Deactivate" else "Activate") },
                onClick = { menu = false; onToggleActive() },
            )
            DropdownMenuItem(
                text = { Text("Delete…") },
                enabled = TestOps.canDelete(t),
                onClick = { menu = false; onDelete() },
            )
        }
    }
}

@Composable
private fun NewTestDialog(app: AppState, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val error = when {
        name.isBlank() -> "Name required"
        app.store.nameTaken(name) -> "A test with this name already exists"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New test") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                isError = error != null && name.isNotEmpty(),
                supportingText = { if (name.isNotEmpty() && error != null) Text(error) },
            )
        },
        confirmButton = { TextButton(enabled = error == null, onClick = { onCreate(name.trim()) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
