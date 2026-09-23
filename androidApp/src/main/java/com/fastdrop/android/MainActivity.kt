package com.fastdrop.android

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fastdrop.core.Peer
import com.fastdrop.core.TransportType
import com.fastdrop.discovery.AndroidNsdDiscoveryProvider
import com.fastdrop.discovery.DiscoveryManager
import com.fastdrop.security.*
import com.fastdrop.transfer.AndroidIncomingFileDestination
import com.fastdrop.transfer.AndroidTransferFileSource
import com.fastdrop.transfer.TransferManager
import com.fastdrop.transport.GenericLanTransport
import dev.whyoleg.cryptography.CryptographyProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var identityStore: AndroidIdentityStore
    private lateinit var peerStore: AndroidTrustedPeerStore
    private lateinit var deviceInfoProvider: AndroidDeviceInfoProvider
    private lateinit var discoveryManager: DiscoveryManager
    private lateinit var transport: GenericLanTransport
    private lateinit var transferManager: TransferManager
    private lateinit var cryptoProvider: CryptographyProvider

    private var serverJob: Job? = null
    
    // UI States
    private val discoveredPeers = mutableStateListOf<com.fastdrop.discovery.DiscoveredPeer>()
    private val appState = mutableStateOf("Ready")
    private val progressState = mutableStateOf(0f)
    private val sasCodeState = mutableStateOf<String?>(null)
    private var pendingVerification: PeerVerification.NewPeer? = null
    private var pendingSecureChannel: SecureChannel? = null
    
    // File picker launcher
    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val selectedPeer = selectedPeerForTransfer
            if (selectedPeer != null) {
                sendFile(uri, selectedPeer)
            }
        }
    }
    private var selectedPeerForTransfer: com.fastdrop.discovery.DiscoveredPeer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        cryptoProvider = CryptographyProvider.Default
        identityStore = AndroidIdentityStore(this, cryptoProvider)
        peerStore = AndroidTrustedPeerStore(this)
        deviceInfoProvider = AndroidDeviceInfoProvider()
        
        val deviceName = kotlinx.coroutines.runBlocking { deviceInfoProvider.getDeviceName() }
        val mDNS = AndroidNsdDiscoveryProvider(this, deviceName)
        discoveryManager = DiscoveryManager(listOf(mDNS))
        
        transport = GenericLanTransport(mDNS)
        transferManager = TransferManager()
        
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FastDropApp(
                        peers = discoveredPeers,
                        state = appState.value,
                        progress = progressState.value,
                        sasCode = sasCodeState.value,
                        onSendClick = { peer -> 
                            selectedPeerForTransfer = peer
                            pickFileLauncher.launch("*/*") 
                        },
                        onAcceptSas = {
                            val verif = pendingVerification
                            val chan = pendingSecureChannel
                            if (verif != null && chan != null) {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    chan.confirmPeer(verif)
                                    // Continuer le transfert s'il était bloqué,
                                    // Mais wait, dans mon archi, confirmPeer débloque et l'appelant continue!
                                    // Donc juste appeler ça va débloquer.
                                }
                                sasCodeState.value = null
                                pendingVerification = null
                            }
                        },
                        onRejectSas = {
                            sasCodeState.value = null
                            pendingVerification = null
                            // In real app, close connection
                        }
                    )
                }
            }
        }

        startServer()
        startDiscovery()
    }

    private fun startDiscovery() {
        lifecycleScope.launch(Dispatchers.IO) {
            discoveryManager.startAll()
            discoveryManager.peers.collect { list ->
                withContext(Dispatchers.Main) {
                    discoveredPeers.clear()
                    discoveredPeers.addAll(list)
                }
            }
        }
    }

    private fun startServer() {
        serverJob = lifecycleScope.launch(Dispatchers.IO) {
            val flow = transport.startHosting()
            flow.collect { rawConnection ->
                launch {
                    try {
                        withContext(Dispatchers.Main) { appState.value = "Incoming connection..." }
                        val secureChannel = SecureChannel(rawConnection, identityStore, peerStore, deviceInfoProvider, cryptoProvider)
                        
                        val verification = secureChannel.handshake()
                        if (verification is PeerVerification.NewPeer) {
                            withContext(Dispatchers.Main) { 
                                pendingVerification = verification
                                pendingSecureChannel = secureChannel
                                sasCodeState.value = verification.sas
                                appState.value = "Waiting for SAS confirmation..."
                            }
                            // Blocking until confirmed by user (this is a simplified UI flow, in reality we'd need a suspendCoroutine)
                            // For MVP, confirmPeer() handles the trust store but the connection is just waiting here.
                            // Actually, confirmPeer() is synchronous in memory updates. We just call it from UI.
                            // Let's use a small polling here for MVP simplicity:
                            while (pendingVerification != null) { kotlinx.coroutines.delay(100) }
                        } else {
                            // nothing
                            withContext(Dispatchers.Main) { appState.value = "Trusted peer connected." }
                        }
                        
                        withContext(Dispatchers.Main) { appState.value = "Receiving file..." }
                        val success = transferManager.receiveFile(
                            connection = secureChannel,
                            onOfferReceived = { offer ->
                                AndroidIncomingFileDestination(this@MainActivity, offer.name)
                            },
                            onProgress = { current, total ->
                                val pct = if (total > 0) current.toFloat() / total.toFloat() else 0f
                                lifecycleScope.launch(Dispatchers.Main) { progressState.value = pct }
                            }
                        )
                        
                        withContext(Dispatchers.Main) { 
                            if (success) {
                                appState.value = "Transfer success!"
                                Toast.makeText(this@MainActivity, "File received successfully", Toast.LENGTH_SHORT).show()
                            } else {
                                appState.value = "Transfer failed."
                            }
                            progressState.value = 0f
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        withContext(Dispatchers.Main) { appState.value = "Error: ${e.message}" }
                    }
                }
            }
        }
    }

    private fun sendFile(uri: Uri, discoveredPeer: com.fastdrop.discovery.DiscoveredPeer) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { appState.value = "Connecting to ${discoveredPeer.displayName}..." }
                val targetIp = discoveredPeer.addresses.firstOrNull() ?: return@launch
                
                val peer = Peer(targetIp, discoveredPeer.displayName ?: "Target", TransportType.LAN, targetIp)
                val rawConn = transport.connect(peer)
                
                withContext(Dispatchers.Main) { appState.value = "Authenticating..." }
                val secureChannel = SecureChannel(rawConn, identityStore, peerStore, deviceInfoProvider, cryptoProvider)
                
                val verification = secureChannel.handshake()
                if (verification is PeerVerification.NewPeer) {
                    withContext(Dispatchers.Main) { 
                        pendingVerification = verification
                        pendingSecureChannel = secureChannel
                        sasCodeState.value = verification.sas
                        appState.value = "Waiting for SAS confirmation..."
                    }
                    while (pendingVerification != null) { kotlinx.coroutines.delay(100) }
                } else {
                    // nothing
                }

                withContext(Dispatchers.Main) { appState.value = "Sending file..." }
                val source = AndroidTransferFileSource(this@MainActivity, uri)
                
                val success = transferManager.sendFile(
                    connection = secureChannel,
                    fileSource = source,
                    onProgress = { current, total ->
                        val pct = if (total > 0) current.toFloat() / total.toFloat() else 0f
                        lifecycleScope.launch(Dispatchers.Main) { progressState.value = pct }
                    }
                )
                
                withContext(Dispatchers.Main) { 
                    appState.value = if (success) "Sent successfully!" else "Send failed."
                    progressState.value = 0f
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) { appState.value = "Error: ${e.message}" }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleScope.launch(Dispatchers.IO) {
            discoveryManager.stopAll()
            transport.stop()
        }
    }
}

@Composable
fun FastDropApp(
    peers: List<com.fastdrop.discovery.DiscoveredPeer>,
    state: String,
    progress: Float,
    sasCode: String?,
    onSendClick: (com.fastdrop.discovery.DiscoveredPeer) -> Unit,
    onAcceptSas: () -> Unit,
    onRejectSas: () -> Unit
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text("FastDrop", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        
        Text("Nearby devices", style = MaterialTheme.typography.titleMedium)
        if (peers.isEmpty()) {
            Text("No FastDrop devices found.\nSearching...", color = MaterialTheme.colorScheme.secondary)
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(peers) { peer ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onSendClick(peer) }) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(peer.displayName ?: "Unknown Device")
                            Text("Available", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        
        if (sasCode != null) {
            Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Security Code: $sasCode")
                    Row {
                        Button(onClick = onAcceptSas) { Text("Accept") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = onRejectSas) { Text("Reject") }
                    }
                }
            }
        }
        
        Spacer(Modifier.height(16.dp))
        Text("Status: $state")
        if (progress > 0f) {
            LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
        }
    }
}
