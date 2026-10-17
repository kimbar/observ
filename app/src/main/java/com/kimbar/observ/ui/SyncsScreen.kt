package com.kimbar.observ.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.kimbar.observ.data.TestHandle
import com.kimbar.observ.model.Fmt
import com.kimbar.observ.model.ItemType
import com.kimbar.observ.model.Sync
import com.kimbar.observ.model.TestOps
import com.kimbar.observ.model.TestTime
import kotlin.math.abs

/** All syncs of a Test, their deltas to the average, and outlier handling (DESIGN.md 6.4). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncsScreen(app: AppState, test: TestHandle, onBack: () -> Unit, onOpenItem: (String) -> Unit) {
    val tt = TestTime(test.data)
    var byDelta by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Sync?>(null) }
    val rows = test.data.syncs.filter { tt.moment(it) != null }.let { list ->
        if (byDelta) list.sortedByDescending { s -> tt.deltaNs(s)?.let { abs(it) } ?: -1L }
        else list.sortedBy { tt.timeline(tt.moment(it)!!) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Syncs") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { FormatSwitch(app) },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 12.dp)) {
            TestClock(app, tt)
            val avg = tt.averageOffsetNs
            Text(
                if (avg == null) "No active syncs: the test is not synced."
                else "${tt.activeSyncs.size} active of ${test.data.syncs.size} · spread ${Fmt.delta(tt.spreadNs ?: 0)} · " +
                    "00:00 at ${Fmt.localFull(avg).substring(11, 23)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "To add a sync, open a MARK, PHOTO or VIDEO from the test and enter the clock reading.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                FilterChip(selected = !byDelta, onClick = { byDelta = false }, label = { Text("By time") })
                FilterChip(selected = byDelta, onClick = { byDelta = true }, label = { Text("By delta") })
            }
            HorizontalDivider()
            LazyColumn(Modifier.weight(1f)) {
                items(rows, key = { it.id }) { s ->
                    SyncRow(s, tt, app, onClick = { editing = s }) { active ->
                        test.update { TestOps.setSyncActive(it, s.id, active) }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    editing?.let { s ->
        var reading by remember(s.id) { mutableIntStateOf(s.readingS) }
        val item = tt.item(s.itemId)
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Correct reading") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${item?.type?.name ?: ""}${s.frame?.let { " frame ${it + 1}" } ?: ""}")
                    ReadingWheels(reading, app.format) { reading = it }
                    if (item?.type == ItemType.PHOTO) Text("+0.5 s is added for photos", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { editing = null; onOpenItem(s.itemId) }) { Text("Open source item") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    test.update { TestOps.setReading(it, s.id, reading) }
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SyncRow(s: Sync, tt: TestTime, app: AppState, onClick: () -> Unit, onActive: (Boolean) -> Unit) {
    val counts = tt.counts(s)
    val item = tt.item(s.itemId)!!
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp).inactiveStyle(counts),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row {
                Text(tt.label(tt.moment(s)!!, app.format), fontFamily = FontFamily.Monospace, modifier = Modifier.width(110.dp))
                Text("read ${Fmt.reading(s.readingS, app.format)}", fontFamily = FontFamily.Monospace)
            }
            Text(
                "${item.type.name}${s.frame?.let { " frame ${it + 1}" } ?: ""} · ${item.file.ifEmpty { "mark" }}" +
                    if (!item.active) " · source inactive" else "",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(tt.deltaNs(s)?.let { Fmt.delta(it) } ?: "", fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 8.dp))
        Switch(checked = s.active, onCheckedChange = onActive, enabled = item.active)
    }
}
