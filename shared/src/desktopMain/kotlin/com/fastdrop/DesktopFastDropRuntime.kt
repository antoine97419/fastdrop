package com.fastdrop

import com.fastdrop.discovery.DesktopMdnsDiscoveryProvider
import com.fastdrop.discovery.DiscoveryManager
import com.fastdrop.security.*
import com.fastdrop.transfer.*
import com.fastdrop.transport.ManualLanTransport
import dev.whyoleg.cryptography.algorithms.EdDSA
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

sealed interface DesktopStatus {
    data object Starting : DesktopStatus
    data object Ready : DesktopStatus
    data class WaitingForDevice(val file: String) : DesktopStatus
    data class Authenticating(val peer: String) : DesktopStatus
    data class Sending(val file: String, val current: Long, val total: Long) : DesktopStatus
    data class Receiving(val file: String, val current: Long, val total: Long) : DesktopStatus
    data class Completed(val file: String) : DesktopStatus
    data object Rejected : DesktopStatus
    data object IntegrityFailed : DesktopStatus
    data object ConnectionLost : DesktopStatus
    data class Error(val message: String) : DesktopStatus
}

data class NearbyPeer(val deviceId: String?, val displayName: String, val trusted: Boolean,
                      val capabilities: Set<String>)
data class SasRequest(val peerName: String, val code: String)
data class DesktopUiState(val status: DesktopStatus = DesktopStatus.Starting,
                          val peers: List<NearbyPeer> = emptyList(), val sas: SasRequest? = null)

/** Owns the receiver, mDNS and Wi-Fi Direct publisher for one window lifetime. */
class DesktopFastDropRuntime internal constructor(
    private val home: File,
    private val bridge: LegacyGoBridgeLifecycle
) {
    constructor() : this(File(System.getProperty("fastdrop.home",
        System.getProperty("user.home") + "/.fastdrop")), WindowsLegacyGoBridgeManager())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(DesktopUiState())
    val state: StateFlow<DesktopUiState> = mutableState
    private val identity = FileIdentityStore(File(home, "identity.json"))
    private val trust = FileTrustedPeerStore(File(home, "trusted_peers.json"))
    private val device = DesktopDeviceInfoProvider()
    private val transport = ManualLanTransport(FastDropConfig.DEFAULT_PORT)
    private val transfers = TransferManager()
    private var discovery: DiscoveryManager? = null
    private var receiver: PersistentReceiverServer<*, *>? = null
    private var receiverJob: Job? = null
    private var startupJob: Job? = null
    private var discoveryJob: Job? = null
    private var selectedFile: File? = null
    private var pendingSas: CompletableDeferred<Boolean>? = null
    private var running = false

    @Synchronized fun start() {
        if (running) return
        running = true
        startupJob = scope.launch {
            try {
                val publicKey = identity.getOrGenerateIdentity().keyPair.publicKey
                    .encodeToByteArray(EdDSA.PublicKey.Format.RAW)
                val deviceId = calculateFingerprint(publicKey)
                val provider = DesktopMdnsDiscoveryProvider(device.getDeviceName(), deviceId)
                discovery = DiscoveryManager(listOf(provider)).also { it.startAll() }
                discoveryJob = launch {
                    discovery!!.peers.collect { peers ->
                        val cards = peers.filter { it.isFastDropService }.map { peer ->
                            NearbyPeer(peer.deviceId, peer.displayName ?: "FastDrop device",
                                peer.deviceId?.let { trust.getPeer(it) } != null, peer.capabilities)
                        }
                        mutableState.value = mutableState.value.copy(peers = cards)
                    }
                }
                bridge.start()
                val server = PersistentReceiverServer(
                    startHosting = { transport.startHosting() },
                    createSecureChannel = { raw -> SecureChannel(raw, identity, trust, device) },
                    authenticate = { secure ->
                        val verification = secure.handshake()
                        val name = when (verification) {
                            is PeerVerification.NewPeer -> verification.friendlyName
                            is PeerVerification.TrustedPeer -> verification.friendlyName
                        }
                        setStatus(DesktopStatus.Authenticating(name))
                        if (verification is PeerVerification.NewPeer) {
                            val decision = CompletableDeferred<Boolean>()
                            pendingSas = decision
                            mutableState.value = mutableState.value.copy(
                                sas = SasRequest(name, verification.sas))
                            val accepted = try { decision.await() } finally {
                                pendingSas = null
                                mutableState.value = mutableState.value.copy(sas = null)
                            }
                            if (accepted) {
                                secure.confirmPeer(verification)
                                mutableState.value = mutableState.value.copy(peers =
                                    mutableState.value.peers.map { peer ->
                                        if (peer.deviceId == verification.deviceId) peer.copy(trusted = true)
                                        else peer
                                    })
                            }
                            accepted
                        } else true
                    },
                    receiveTransfer = { secure ->
                        val outgoing = synchronized(this@DesktopFastDropRuntime) {
                            selectedFile.also { selectedFile = null }
                        }
                        if (outgoing != null) {
                            var outcome = TransferOutcome.FAILED
                            val success = transfers.sendFile(secure, DesktopTransferFileSource(outgoing),
                                onProgress = { current, total -> setStatus(DesktopStatus.Sending(outgoing.name, current, total)) },
                                onOutcome = { outcome = it })
                            setStatus(when (outcome) {
                                TransferOutcome.SUCCESS -> DesktopStatus.Completed(outgoing.name)
                                TransferOutcome.REJECTED -> DesktopStatus.Rejected
                                TransferOutcome.HASH_MISMATCH -> DesktopStatus.IntegrityFailed
                                TransferOutcome.CONNECTION_LOST -> DesktopStatus.ConnectionLost
                                TransferOutcome.FAILED -> DesktopStatus.Error("Transfer failed")
                            })
                            success
                        } else {
                            var fileName = "file"
                            val success = transfers.receiveFile(secure,
                                onOfferReceived = { offer ->
                                    fileName = transfers.sanitizeFilename(offer.name)
                                    DesktopIncomingFileDestination(File(
                                        System.getProperty("user.home"), "Downloads/FastDrop/$fileName"))
                                }, onProgress = { current, total ->
                                    setStatus(DesktopStatus.Receiving(fileName, current, total))
                                })
                            setStatus(if (success) DesktopStatus.Completed(fileName)
                                      else DesktopStatus.Error("Transfer failed"))
                            success
                        }
                    },
                    closeSecureChannel = { it.close() },
                    closeRawConnection = { it.close() },
                    stopHosting = { transport.stop() },
                    onListening = {
                        if (mutableState.value.status is DesktopStatus.Completed ||
                            mutableState.value.status is DesktopStatus.Rejected ||
                            mutableState.value.status is DesktopStatus.IntegrityFailed ||
                            mutableState.value.status is DesktopStatus.ConnectionLost ||
                            mutableState.value.status is DesktopStatus.Error) {
                            scope.launch { delay(3000); setStatus(DesktopStatus.Ready) }
                        } else if (selectedFile == null) setStatus(DesktopStatus.Ready)
                        else setStatus(DesktopStatus.WaitingForDevice(selectedFile!!.name))
                    },
                    onSessionMessage = { message ->
                        if (message.startsWith("Client session failed"))
                            setStatus(DesktopStatus.ConnectionLost)
                    }
                )
                receiver = server
                receiverJob = launch { server.run() }
                launch {
                    val failure = bridge.awaitUnexpectedExit()
                    setStatus(DesktopStatus.Error(failure.message))
                    server.stop()
                }
            } catch (error: Exception) {
                setStatus(DesktopStatus.Error(error.message ?: "FastDrop could not start"))
                scope.launch { stop() }
            }
        }
    }

    @Synchronized fun send(file: File) {
        require(file.isFile)
        selectedFile = file
        setStatus(DesktopStatus.WaitingForDevice(file.name))
    }

    fun confirmSas(accept: Boolean) { pendingSas?.complete(accept) }

    suspend fun stop() {
        if (!running) return
        running = false
        pendingSas?.complete(false)
        startupJob?.cancelAndJoin()
        @Suppress("UNCHECKED_CAST")
        (receiver as? PersistentReceiverServer<Any, Any>)?.stop()
        receiverJob?.cancelAndJoin()
        discoveryJob?.cancelAndJoin()
        discovery?.stopAll()
        bridge.stop()
        (bridge as? WindowsLegacyGoBridgeManager)?.close()
        scope.cancel()
    }

    private fun setStatus(status: DesktopStatus) {
        mutableState.value = mutableState.value.copy(status = status)
    }
}
