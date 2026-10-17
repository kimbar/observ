package com.kimbar.observ

import android.Manifest
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.kimbar.observ.data.Session
import com.kimbar.observ.ui.AppState
import com.kimbar.observ.ui.HomeScreen
import com.kimbar.observ.ui.SessionScreen
import com.kimbar.observ.ui.SyncScreen
import java.io.File

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

@Composable
private fun App() {
    val context = LocalContext.current
    val app = remember { AppState(context) }
    fun granted() = permissions.all { ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
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

    // Navigation: the open session (by folder path, survives recreation) and whether Sync is on top.
    var sessionPath by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var syncOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val session = sessionPath?.let { Session(File(it)) }

    when {
        syncOpen -> {
            BackHandler { syncOpen = false }
            SyncScreen(app, session, onBack = { syncOpen = false })
        }
        session != null -> {
            BackHandler { sessionPath = null }
            SessionScreen(app, session, onBack = { sessionPath = null }, onSync = { syncOpen = true })
        }
        else -> HomeScreen(app, onOpen = { sessionPath = it.dir.absolutePath }, onSync = { syncOpen = true })
    }
}
