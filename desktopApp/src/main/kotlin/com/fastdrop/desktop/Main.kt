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
                Text(when (val status = state.status) {
                    DesktopStatus.Starting -> "Starting..."
                    DesktopStatus.Ready -> "Ready to receive"
                    is DesktopStatus.WaitingForDevice -> "Waiting for device... ${status.file}"
                    is DesktopStatus.Authenticating -> "Authenticating ${status.peer}"
                    is DesktopStatus.Sending -> "Sending ${status.file}"
                    is DesktopStatus.Receiving -> "Receiving ${status.file}"
                    is DesktopStatus.Completed -> "Transfer complete: ${status.file}"
                    DesktopStatus.Rejected -> "Transfer rejected"
                    DesktopStatus.IntegrityFailed -> "Integrity check failed"
                    DesktopStatus.ConnectionLost -> "Connection lost"
                    is DesktopStatus.Error -> status.message
                })
                val progress = when (val status = state.status) {
                    is DesktopStatus.Sending -> status.current to status.total
                    is DesktopStatus.Receiving -> status.current to status.total
                    else -> null
                }
                if (progress != null) {
                    val (current, total) = progress
                    LinearProgressIndicator((current.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f))
                    Text("$current / $total bytes")
                }
                Spacer(Modifier.height(24.dp))
                Text("Nearby devices", style = MaterialTheme.typography.h6)
                LazyColumn(Modifier.weight(1f)) {
                    items(state.peers) { peer ->
                        Card(Modifier.fillMaxWidth().padding(vertical = 6.dp), elevation = 2.dp) {
                            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column {
                                    Text(peer.displayName)
                                    Text(if (peer.trusted) "Trusted" else "Available")
                                }
                                Button(onClick = {
                                    val dialog = FileDialog(null as Frame?, "Send files", FileDialog.LOAD)
                                    dialog.isVisible = true
                                    val name = dialog.file
                                    if (name != null) viewModel.send(java.io.File(dialog.directory, name))
                                    dialog.dispose()
                                }) { Text("Send files") }
                            }
                        }
                    }
                }
                Text("Ready", style = MaterialTheme.typography.caption)
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
