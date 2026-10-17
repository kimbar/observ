package com.kimbar.observ.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kimbar.observ.data.Exporter
import com.kimbar.observ.data.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(app: AppState, onOpen: (Session) -> Unit, onSync: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    val sessions = remember(refresh) { Session.list(context) }
    var title by remember { mutableStateOf("") }
    var toDelete by remember { mutableStateOf<Session?>(null) }
    var exporting by remember { mutableStateOf<Float?>(null) }

    Scaffold(topBar = { TopAppBar(title = { Text("observ") }) }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 12.dp).fillMaxSize()) {
            LiveClock(app, fontSize = 48)
            SyncStatus(app)
            OutlinedButton(onClick = onSync, modifier = Modifier.padding(vertical = 8.dp)) { Text("Sync clock…") }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text("New session name (optional)") },
                    singleLine = true, modifier = Modifier.weight(1f),
                )
                Button(onClick = {
                    val s = Session.create(context, app.time, title)
                    title = ""
                    onOpen(s)
                }) { Text("Start") }
            }
            exporting?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) }
            Text("Sessions", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(sessions, key = { it.name }) { s ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(s) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name)
                            val n = s.dir.listFiles()?.size?.minus(1)?.coerceAtLeast(0) ?: 0
                            Text("$n media files", style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(enabled = exporting == null, onClick = {
                            exporting = 0f
                            scope.launch {
                                try {
                                    val uri = withContext(Dispatchers.IO) {
                                        Exporter.exportToDownloads(context, s) { exporting = it }
                                    }
                                    shareZip(context, uri)
                                } catch (e: Exception) {
                                    toast(context, "Export failed: ${e.message}")
                                } finally {
                                    exporting = null
                                }
                            }
                        }) { Icon(Icons.Filled.Share, "Export and share") }
                        IconButton(onClick = { toDelete = s }) { Icon(Icons.Filled.Delete, "Delete") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    toDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete ${s.name}?") },
            text = { Text("All photos, videos, audio and notes of this session are removed from the phone. Exported zips in Downloads are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    s.dir.deleteRecursively()
                    toDelete = null
                    refresh++
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}
