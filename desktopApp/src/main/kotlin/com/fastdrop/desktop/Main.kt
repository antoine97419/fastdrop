package com.fastdrop.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.fastdrop.DesktopStatus
import com.fastdrop.DesktopRuntimePresentation
import kotlinx.coroutines.runBlocking
import java.awt.FileDialog
import java.awt.Frame

fun main() = application {
    val viewModel = remember { DesktopFastDropViewModel().also { it.start() } }
    val state by viewModel.state.collectAsState()
    Window(onCloseRequest = {
        runBlocking { viewModel.stop() }
        exitApplication()
    }, title = "FastDrop") {
        MaterialTheme {
            Column(Modifier.fillMaxSize().padding(24.dp)) {
                Text("FastDrop", style = MaterialTheme.typography.h4)
                Spacer(Modifier.height(12.dp))
                Card(Modifier.fillMaxWidth(), elevation = 1.dp) {
                    Column(Modifier.padding(14.dp)) {
                        Text(DesktopRuntimePresentation.message(state.status),
                            style = MaterialTheme.typography.subtitle1)
                        DesktopRuntimePresentation.progress(state.status)?.let { progress ->
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(progress.fraction, Modifier.fillMaxWidth())
                            Spacer(Modifier.height(5.dp))
                            Text(progress.label, style = MaterialTheme.typography.caption)
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text("Nearby devices", style = MaterialTheme.typography.h6)
                LazyColumn(Modifier.weight(1f)) {
                    if (state.peers.isEmpty()) item {
                        Column(Modifier.fillMaxWidth().padding(vertical = 28.dp)) {
                            Text("No nearby FastDrop devices", style = MaterialTheme.typography.subtitle1)
                            Text("Keep FastDrop open on the other device.",
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f))
                        }
                    }
                    items(state.peers) { peer ->
                        Card(Modifier.fillMaxWidth().padding(vertical = 6.dp), elevation = 2.dp) {
                            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Row {
                                    Text("▣", style = MaterialTheme.typography.h5,
                                        color = MaterialTheme.colors.primary)
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text(peer.displayName, style = MaterialTheme.typography.subtitle1)
                                        Text(if (peer.trusted) "Trusted" else "Available",
                                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f))
                                    }
                                }
                                Button(onClick = {
                                    val dialog = FileDialog(null as Frame?, "Send files", FileDialog.LOAD)
                                    try {
                                        dialog.isVisible = true
                                        val name = dialog.file
                                        if (name != null) viewModel.send(java.io.File(dialog.directory, name), peer.deviceId)
                                    } finally { dialog.dispose() }
                                }) { Text("Send files") }
                            }
                        }
                    }
                }
            }
            state.sas?.let { sas ->
                AlertDialog(onDismissRequest = { viewModel.confirmSas(false) },
                    title = { Text("Verify security code") },
                    text = { Column { Text(sas.peerName); Text(sas.code); Text("Does this code match on both devices?") } },
                    confirmButton = { TextButton(onClick = { viewModel.confirmSas(true) }) { Text("Trust device") } },
                    dismissButton = { TextButton(onClick = { viewModel.confirmSas(false) }) { Text("Cancel") } })
            }
        }
    }
}
