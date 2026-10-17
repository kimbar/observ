package com.kimbar.observ

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.kimbar.observ.ui.AppState
import com.kimbar.observ.ui.HomeScreen
import com.kimbar.observ.ui.ItemScreen
import com.kimbar.observ.ui.SyncsScreen
import com.kimbar.observ.ui.TestScreen

private val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

/**
 * Navigation is a back stack of routes kept as one saveable string:
 * `test/<dir>`, `item/<dir>/<itemId>`, `syncs/<dir>`, separated by '|'.
 */
@Composable
private fun App() {
    val context = LocalContext.current
    val app = remember { AppState(context) }
    fun granted() = permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    var hasPermissions by remember { mutableStateOf(granted()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPermissions = granted()
    }
    LaunchedEffect(Unit) { if (!hasPermissions) launcher.launch(permissions) }

    if (!hasPermissions) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("observ needs the camera and microphone to record photos, videos and audio notes.")
            Button(onClick = { launcher.launch(permissions) }) { Text("Grant permissions") }
        }
        return
    }

    var stackText by rememberSaveable { mutableStateOf("") }
    val stack = stackText.split('|').filter { it.isNotEmpty() }
    fun push(route: String) { stackText = (stack + route).joinToString("|") }
    fun pop() { stackText = stack.dropLast(1).joinToString("|") }

    val top = stack.lastOrNull()?.split('/')
    val test = top?.getOrNull(1)?.let { app.store.find(it) }
    if (top != null && test == null) {
        LaunchedEffect(stackText) { stackText = "" }
        return
    }
    if (top != null) BackHandler { pop() }

    when (top?.first()) {
        null -> HomeScreen(app) { h -> push("test/${h.dir.name}") }
        "test" -> TestScreen(
            app, test!!, onBack = { pop() },
            onOpenItem = { push("item/${test.dir.name}/${it.id}") },
            onSyncs = { push("syncs/${test.dir.name}") },
        )
        "item" -> ItemScreen(
            app, test!!, top!![2], onBack = { pop() },
            onSyncs = { push("syncs/${test.dir.name}") },
        )
        "syncs" -> SyncsScreen(
            app, test!!, onBack = { pop() },
            onOpenItem = { push("item/${test.dir.name}/$it") },
        )
    }
}
