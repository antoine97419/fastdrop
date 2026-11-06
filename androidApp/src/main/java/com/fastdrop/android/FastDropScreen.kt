package com.fastdrop.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fastdrop.transfer.ControlMessage

@Composable
internal fun FastDropApp(
    devices: List<FastDropDeviceCard>,
    status: FastDropUiStatus,
    transfer: FastDropUiStatus.Transferring?,
    sasCode: String?,
    incomingOffer: ControlMessage.FileOffer?,
    incomingPeerName: String?,
    onAcceptOffer: () -> Unit,
    onRejectOffer: () -> Unit,
    onReceiveFiles: () -> Unit,
    onSendClick: (com.fastdrop.discovery.DiscoveredPeer) -> Unit,
    onAcceptSas: () -> Unit,
    onRejectSas: () -> Unit,
    onSendFiles: () -> Unit,
    onClearStatus: () -> Unit
) {
    LaunchedEffect(status) {
        if (status is FastDropUiStatus.Sent ||
            status is FastDropUiStatus.Received ||
            status is FastDropUiStatus.Complete ||
            status is FastDropUiStatus.Rejected ||
            status is FastDropUiStatus.Failed ||
            status is FastDropUiStatus.ConnectionLost ||
            status is FastDropUiStatus.IntegrityFailed
        ) {
            kotlinx.coroutines.delay(6_000)
            onClearStatus()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text("FastDrop", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text("Share files with nearby devices", color = MaterialTheme.colorScheme.secondary)

        Spacer(Modifier.height(24.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onSendFiles, modifier = Modifier.weight(1f)) { Text("Send files") }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = onReceiveFiles, modifier = Modifier.weight(1f)) { Text("Receive files") }
        }

        Spacer(Modifier.height(28.dp))
        Text("Nearby devices", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (devices.isEmpty()) {
                item {
                    Text(
                        "No nearby FastDrop devices",
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
            } else {
                items(devices) { device ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)
                            .clickable { onSendClick(device.peer) }
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(device.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (device.trusted) "Trusted" else "Available",
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }
        }

        if (transfer != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(transfer.fileName, style = MaterialTheme.typography.titleMedium)
                    Text(transfer.peerName, color = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = transfer.percent / 100f,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        (if (transfer.sending) "Sending" else "Receiving") + "… " +
                            transfer.percent + "%  ·  " +
                            FastDropUiPresentation.bytes(transfer.current) + " / " +
                            FastDropUiPresentation.bytes(transfer.total)
                    )
                }
            }
        } else {
            val message = when (status) {
                FastDropUiStatus.Idle -> null
                FastDropUiStatus.Searching -> "Searching for devices…"
                FastDropUiStatus.Selecting -> "Selecting file…"
                FastDropUiStatus.Connecting -> "Connecting…"
                FastDropUiStatus.Authenticating -> "Authenticating…"
                FastDropUiStatus.WaitingForApproval -> "Waiting for approval…"
                FastDropUiStatus.WaitingForFile -> "Ready to receive"
                FastDropUiStatus.Sending -> "Sending…"
                FastDropUiStatus.Receiving -> "Receiving…"
                FastDropUiStatus.Sent -> "Sent successfully"
                FastDropUiStatus.Received -> "Received successfully"
                FastDropUiStatus.Complete -> "Transfer complete"
                FastDropUiStatus.Rejected -> "Transfer rejected"
                FastDropUiStatus.ConnectionLost -> "Connection lost"
                FastDropUiStatus.IntegrityFailed -> "File integrity check failed"
                FastDropUiStatus.Failed -> "Transfer failed"
                is FastDropUiStatus.Transferring -> null
            }
            if (message != null) {
                Text(message, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }

    if (incomingOffer != null) {
        AlertDialog(
            onDismissRequest = onRejectOffer,
            title = { Text("Incoming file") },
            text = {
                Column {
                    Text("From: " + FastDropUiPresentation.cleanName(incomingPeerName))
                    Spacer(Modifier.height(8.dp))
                    Text("Name: " + incomingOffer.name)
                    Text("Size: " + FastDropUiPresentation.bytes(incomingOffer.size))
                }
            },
            confirmButton = { TextButton(onClick = onAcceptOffer) { Text("Accept") } },
            dismissButton = { TextButton(onClick = onRejectOffer) { Text("Reject") } }
        )
    }

    if (sasCode != null) {
        AlertDialog(
            onDismissRequest = onRejectSas,
            title = { Text("Verify security code") },
            text = {
                Column {
                    Text(sasCode, style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("Does this code match on both devices?")
                }
            },
            confirmButton = { TextButton(onClick = onAcceptSas) { Text("Trust device") } },
            dismissButton = { TextButton(onClick = onRejectSas) { Text("Cancel") } }
        )
    }
}
