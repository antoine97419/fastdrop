package com.fastdrop.android

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
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
import com.fastdrop.FastDropDiagnostics
import com.fastdrop.core.Peer
import com.fastdrop.core.TransportType
import com.fastdrop.discovery.AndroidNsdDiscoveryProvider
import com.fastdrop.discovery.DiscoveryManager
import com.fastdrop.security.*
import com.fastdrop.transfer.AndroidIncomingFileDestination
import com.fastdrop.transfer.AndroidTransferFileSource
import com.fastdrop.transfer.TransferManager
import com.fastdrop.transfer.TransferOutcome
import com.fastdrop.transfer.ControlMessage
import com.fastdrop.transport.Connection
import com.fastdrop.transport.GenericLanTransport
import dev.whyoleg.cryptography.CryptographyProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var identityStore: AndroidIdentityStore
    private lateinit var peerStore: AndroidTrustedPeerStore
    private lateinit var deviceInfoProvider: AndroidDeviceInfoProvider
    private lateinit var discoveryManager: DiscoveryManager
    private lateinit var transport: com.fastdrop.transport.Transport
    private lateinit var transferManager: TransferManager
    private lateinit var cryptoProvider: CryptographyProvider

    private var serverJob: Job? = null
    
    // UI States
    private val discoveredPeers = mutableStateListOf<com.fastdrop.discovery.DiscoveredPeer>()
    private val appState = mutableStateOf("Ready")
    private val progressState = mutableStateOf(0f)
    private val transferUiState = mutableStateOf<FastDropUiStatus.Transferring?>(null)
    private val trustedDeviceNames = mutableStateListOf<String>()
    private val sasCodeState = mutableStateOf<String?>(null)
    private var pendingVerification: PeerVerification.NewPeer? = null
    private var pendingSecureChannel: SecureChannel? = null
    private var pendingLegacySasDecision: CompletableDeferred<Boolean>? = null
    private var pendingOfferDecision: CompletableDeferred<Boolean>? = null
    private val incomingOfferState = mutableStateOf<ControlMessage.FileOffer?>(null)
    private val incomingPeerNameState = mutableStateOf<String?>(null)
    private var legacyPickerPending = false
    private var legacyTransferInProgress = false
    private var legacySendOutcome: TransferOutcome? = null
    private lateinit var legacySendWorkflow: PickerFirstLegacySendWorkflow<Uri, Connection, SecureChannel>
    
    // File picker launcher
    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (legacyPickerPending) {
            legacyPickerPending = false
            legacyTransferInProgress = true
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    legacySendWorkflow.onPickerResult(uri)
                } finally {
                    withContext(Dispatchers.Main) {
                        legacyTransferInProgress = false
                    }
                }
            }
        } else if (uri != null) {
            val selectedPeer = selectedPeerForTransfer
            if (selectedPeer != null) {
                sendFile(uri, selectedPeer)
            }
        }
    }
    private var selectedPeerForTransfer: com.fastdrop.discovery.DiscoveredPeer? = null

    // Permissions launcher
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.values.all { it }) {
            // Permissions granted
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FastDropDiagnostics.enabled = Log.isLoggable("FastDrop", Log.DEBUG)
        
        // Request permissions
        val permissionsToRequest = mutableListOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(android.Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P) {
            permissionsToRequest.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        
        cryptoProvider = CryptographyProvider.Default
        identityStore = AndroidIdentityStore(this, cryptoProvider)
        peerStore = AndroidTrustedPeerStore(this)
        deviceInfoProvider = AndroidDeviceInfoProvider()
        
        val deviceName = kotlinx.coroutines.runBlocking { deviceInfoProvider.getDeviceName() }
        
        // P2P Setup
        val hasWifiDirect = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_WIFI_DIRECT)
        
        val discoveryProviders = mutableListOf<com.fastdrop.discovery.DiscoveryProvider>()
        val mDNS = AndroidNsdDiscoveryProvider(this, deviceName)
        discoveryProviders.add(mDNS)

        if (hasWifiDirect) {
            val wifiP2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as android.net.wifi.p2p.WifiP2pManager
            val p2pChannel = wifiP2pManager.initialize(this, mainLooper, null)
            val p2pDiscovery = com.fastdrop.discovery.AndroidWifiP2pDiscoveryProvider(this, wifiP2pManager, p2pChannel)
            val p2pTransport = com.fastdrop.transport.AndroidWifiP2pTransport(this, wifiP2pManager, p2pChannel, p2pDiscovery)
            discoveryProviders.add(p2pDiscovery)
            
            // For testing P2P, we just use p2pTransport. (Real app would have a unified transport or selection UI)
            transport = p2pTransport
        } else {
            Toast.makeText(this, "Nearby sharing is unavailable on this device", Toast.LENGTH_LONG).show()
            transport = GenericLanTransport(mDNS)
        }

        // Combine discovery
        discoveryManager = DiscoveryManager(discoveryProviders)
        transferManager = TransferManager()
        legacySendWorkflow = createLegacySendWorkflow()
        
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FastDropApp(
                        devices = FastDropUiPresentation.devices(discoveredPeers, trustedDeviceNames.toSet()),
                        status = FastDropUiPresentation.fromWorkflow(appState.value, discoveredPeers.any { it.isFastDropService }),
                        transfer = transferUiState.value,
                        sasCode = sasCodeState.value,
                        incomingOffer = incomingOfferState.value,
                        incomingPeerName = incomingPeerNameState.value,
                        onSendClick = { peer -> 
                            appState.value = PickerFirstLegacySendWorkflow.SELECTING_FILE
                            selectedPeerForTransfer = peer
                            pickFileLauncher.launch("*/*") 
                        },
                        onAcceptSas = {
                            val legacyDecision = pendingLegacySasDecision
                            if (legacyDecision != null) {
                                legacyDecision.complete(true)
                            } else {
                                val verif = pendingVerification
                                val chan = pendingSecureChannel
                                if (verif != null && chan != null) {
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        chan.confirmPeer(verif)
                                    }
                                    sasCodeState.value = null
                                    pendingVerification = null
                                }
                            }
                        },
                        onRejectSas = {
                            val legacyDecision = pendingLegacySasDecision
                            if (legacyDecision != null) {
                                legacyDecision.complete(false)
                            } else {
                                sasCodeState.value = null
                                pendingVerification = null
                            }
                        },
                        onAcceptOffer = { pendingOfferDecision?.complete(true) },
                        onRejectOffer = { pendingOfferDecision?.complete(false) },
                        onReceiveFiles = { startLegacyGoReceive() },
                        onClearStatus = { appState.value = "Ready" },
                        onSendFiles = {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                startLegacyGoFileSelection()
                            } else {
                                appState.value = "Transfer failed"
                            }
                        }
                    )
                }
            }
        }

        startServer()
        startDiscovery()
    }

    private fun showProgress(sending: Boolean, fileName: String, peerName: String, current: Long, total: Long) {
        lifecycleScope.launch(Dispatchers.Main) {
            if (!appState.value.startsWith(if (sending) "Sending" else "Receiving")) return@launch
            transferUiState.value = FastDropUiStatus.Transferring(
                sending, fileName, FastDropUiPresentation.cleanName(peerName), current, total
            )
        }
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
                        var incomingFileName = "File"
                        val success = transferManager.receiveFile(
                            connection = secureChannel,
                            onOfferReceived = { offer ->
                                incomingFileName = transferManager.sanitizeFilename(offer.name)
                                AndroidIncomingFileDestination(this@MainActivity, offer.name)
                            },
                            onProgress = { current, total ->
                                val pct = if (total > 0) current.toFloat() / total.toFloat() else 0f
                                showProgress(false, incomingFileName, incomingPeerNameState.value ?: "FastDrop device", current, total)
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
                    transferUiState.value = null
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        withContext(Dispatchers.Main) {
                    appState.value = "Error: ${e.message}"
                    transferUiState.value = null
                }
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
                
                val transportType = if (discoveredPeer.source == com.fastdrop.discovery.DiscoveryType.WIFI_DIRECT) {
                    TransportType.WIFI_DIRECT
                } else {
                    TransportType.LAN
                }
                val peer = Peer(discoveredPeer.discoveryId, discoveredPeer.displayName ?: "Target", transportType, targetIp)
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
                }

                withContext(Dispatchers.Main) { appState.value = "Sending file..." }
                val source = AndroidTransferFileSource(this@MainActivity, uri)
                
                var sendOutcome: TransferOutcome? = null
                val success = transferManager.sendFile(
                    connection = secureChannel,
                    fileSource = source,
                    onProgress = { current, total ->
                        showProgress(true, source.name, FastDropUiPresentation.cleanName(discoveredPeer.displayName), current, total)
                    },
                    onOutcome = { sendOutcome = it }
                )
                
                withContext(Dispatchers.Main) { 
                    appState.value = FastDropUiPresentation.outcomeLabel(sendOutcome ?: if (success) TransferOutcome.SUCCESS else TransferOutcome.FAILED)
                    progressState.value = 0f
                            transferUiState.value = null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) { appState.value = "Error: ${e.message}" }
            }
        }
    }

    private fun startLegacyGoFileSelection() {
        if (legacyPickerPending || legacyTransferInProgress) return

        legacyPickerPending = true
        selectedPeerForTransfer = null
        lifecycleScope.launch(Dispatchers.Main) {
            legacySendWorkflow.startSelection()
        }
    }

    private fun createLegacySendWorkflow() =
        PickerFirstLegacySendWorkflow<Uri, Connection, SecureChannel>(
            openPicker = {
                withContext(Dispatchers.Main) {
                    pickFileLauncher.launch("*/*")
                }
            },
            connect = {
                com.fastdrop.transport.AndroidLegacyGoConnector(this@MainActivity).connect()
            },
            authenticate = { rawConnection ->
                authenticateLegacyConnection(rawConnection)
            },
            send = { secureChannel, uri ->
                val source = AndroidTransferFileSource(this@MainActivity, uri)
                legacySendOutcome = null
                transferManager.sendFile(
                    connection = secureChannel,
                    fileSource = source,
                    onProgress = { current, total ->
                        val progress = if (total > 0) current.toFloat() / total.toFloat() else 0f
                        lifecycleScope.launch(Dispatchers.Main) {
                            progressState.value = progress
                            transferUiState.value = FastDropUiStatus.Transferring(true, source.name, incomingPeerNameState.value ?: "Windows PC", current, total)
                        }
                    },
                    onOutcome = { legacySendOutcome = it }
                )
            },
            closeSecure = { secureChannel ->
                secureChannel.close()
            },
            closeRaw = { rawConnection ->
                rawConnection.close()
            },
            updateState = { state ->
                withContext(Dispatchers.Main) {
                    appState.value = if (state == PickerFirstLegacySendWorkflow.SEND_FAILED)
                        FastDropUiPresentation.outcomeLabel(legacySendOutcome ?: TransferOutcome.FAILED)
                    else state
                    if (state == PickerFirstLegacySendWorkflow.SENT ||
                        state == PickerFirstLegacySendWorkflow.SEND_FAILED ||
                        state.startsWith("Error:")
                    ) {
                        progressState.value = 0f
                            transferUiState.value = null
                    }
                }
            },
            reportCloseError = { closeError ->
                Log.w("FastDrop", "Failed to close transfer session", closeError)
            },
            reportFailure = { Log.e("FastDrop", "Send failed", it) }
        )

    private suspend fun authenticateLegacyConnection(rawConnection: Connection): SecureChannel {
        val secureChannel = SecureChannel(
            rawConnection,
            identityStore,
            peerStore,
            deviceInfoProvider,
            cryptoProvider
        )

        val verification = secureChannel.handshake()
        withContext(Dispatchers.Main) {
            incomingPeerNameState.value = when (verification) {
                is PeerVerification.NewPeer -> verification.friendlyName
                is PeerVerification.TrustedPeer -> verification.friendlyName
            }
        }
        if (verification is PeerVerification.TrustedPeer) {
            withContext(Dispatchers.Main) {
                val name = FastDropUiPresentation.cleanName(verification.friendlyName)
                if (name !in trustedDeviceNames) trustedDeviceNames.add(name)
            }
        }
        if (verification is PeerVerification.NewPeer) {
            val decision = CompletableDeferred<Boolean>()
            withContext(Dispatchers.Main) {
                pendingLegacySasDecision = decision
                sasCodeState.value = verification.sas
            }

            val accepted = try {
                decision.await()
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    pendingLegacySasDecision = null
                    sasCodeState.value = null
                }
            }

            if (!accepted) {
                throw IllegalStateException("SAS confirmation rejected")
            }
            secureChannel.confirmPeer(verification)
            withContext(Dispatchers.Main) {
                val name = FastDropUiPresentation.cleanName(verification.friendlyName)
                if (name !in trustedDeviceNames) trustedDeviceNames.add(name)
            }
        }

        return secureChannel
    }

    private fun startLegacyGoReceive() {
        if (legacyPickerPending || legacyTransferInProgress) return
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            appState.value = "Transfer failed"
            return
        }
        legacyTransferInProgress = true
        lifecycleScope.launch(Dispatchers.IO) {
            var receivedDestination: AndroidIncomingFileDestination? = null
            var rejected = false
            val workflow = LegacyGoReceiveWorkflow<Connection, SecureChannel>(
                connect = { com.fastdrop.transport.AndroidLegacyGoConnector(this@MainActivity).connect() },
                authenticate = { raw -> authenticateLegacyConnection(raw) },
                receive = { secure ->
                    transferManager.receiveFile(
                        connection = secure,
                        onOfferReceived = { offer ->
                            val decision = CompletableDeferred<Boolean>()
                            withContext(Dispatchers.Main) {
                                pendingOfferDecision = decision
                                incomingOfferState.value = offer
                            }
                            val accepted = try {
                                decision.await()
                            } finally {
                                withContext(NonCancellable + Dispatchers.Main) {
                                    pendingOfferDecision = null
                                    incomingOfferState.value = null
                                }
                            }
                            if (!accepted) {
                                rejected = true
                                null
                            } else {
                                AndroidIncomingFileDestination(
                                    this@MainActivity,
                                    transferManager.sanitizeFilename(offer.name)
                                ).also { receivedDestination = it }
                            }
                        },
                        onProgress = { current, total ->
                            lifecycleScope.launch(Dispatchers.Main) {
                                val percent = if (total > 0) current.toFloat() / total else 1f
                                progressState.value = percent
                                transferUiState.value = FastDropUiStatus.Transferring(false, receivedDestination?.displayPath?.substringAfterLast('/') ?: "File", incomingPeerNameState.value ?: "Windows PC", current, total)
                                appState.value = "Receiving " + (receivedDestination?.displayPath?.substringAfterLast('/') ?: "file") +
                                    " — " + (percent * 100).toInt() + "%"
                            }
                        }
                    )
                },
                closeSecure = { it.close() },
                closeRaw = { it.close() },
                updateState = { state -> withContext(Dispatchers.Main) { appState.value = state } },
                reportCloseError = { Log.w("FastDrop", "Failed to close receive session", it) },
                reportFailure = { Log.e("FastDrop", "Receive failed", it) }
            )
            try {
                val success = workflow.run()
                withContext(Dispatchers.Main) {
                    appState.value = when {
                        success -> "Received successfully! " + (receivedDestination?.displayPath ?: "")
                        rejected -> "File rejected."
                        appState.value.startsWith("Error:") -> appState.value
                        else -> "Receive failed."
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    pendingOfferDecision?.complete(false)
                    pendingOfferDecision = null
                    incomingOfferState.value = null
                    incomingPeerNameState.value = null
                    progressState.value = 0f
                            transferUiState.value = null
                    legacyTransferInProgress = false
                }
            }
        }
    }

    override fun onDestroy() {
        pendingLegacySasDecision?.complete(false)
        pendingOfferDecision?.complete(false)
        super.onDestroy()
        lifecycleScope.launch(Dispatchers.IO) {
            discoveryManager.stopAll()
            transport.stop()
        }
    }
}
