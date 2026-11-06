package com.fastdrop

import com.fastdrop.discovery.DesktopMdnsDiscoveryProvider
import com.fastdrop.discovery.DiscoveryManager
import com.fastdrop.discovery.DiscoveredPeer
import com.fastdrop.core.Peer
import com.fastdrop.core.TransportType
import com.fastdrop.session.SessionProtocol
import com.fastdrop.session.SessionGate
import com.fastdrop.session.IncomingSessionResult
import com.fastdrop.security.*
import com.fastdrop.transfer.*
import com.fastdrop.transport.ManualLanTransport
import dev.whyoleg.cryptography.algorithms.EdDSA
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap

sealed interface DesktopStatus {
    data object Starting : DesktopStatus
    data object Ready : DesktopStatus
    data class Connecting(val peer: String) : DesktopStatus
    data object Busy : DesktopStatus
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
                      val capabilities: Set<String>, val sessionVersion: Int? = null)
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
    private val activeTransfer = SessionGate()
    private data class PreparedReceiver(val channel: SecureChannel, val finished: CompletableDeferred<Unit>)
    private val prepared = ConcurrentHashMap<String, PreparedReceiver>()
    private val authenticatedIds = ConcurrentHashMap<SecureChannel, String>()
    @Volatile private var discoveredPeers: List<DiscoveredPeer> = emptyList()
    private var pendingSas: CompletableDeferred<Boolean>? = null
    private var running = false

    @Synchronized fun start() {
        if (running) return
        FastDropDiagnostics.enabled = System.getProperty("fastdrop.debug") == "true"
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
                        discoveredPeers = peers
                        val present = peers.mapNotNull { it.deviceId }.toSet()
                        prepared.entries.filter { it.key !in present }.forEach { entry ->
                            if (prepared.remove(entry.key, entry.value)) entry.value.finished.complete(Unit)
                        }
                        val cards = peers.filter { it.isFastDropService }.map { peer ->
                            NearbyPeer(peer.deviceId, peer.displayName ?: "FastDrop device",
                                peer.deviceId?.let { trust.getPeer(it) } != null,
                                peer.capabilities, peer.sessionVersion)
                        }
                        mutableState.value = mutableState.value.copy(peers = cards)
                    }
                }
                bridge.start()
                val server = PersistentReceiverServer(
                    startHosting = { transport.startHosting() },
                    createSecureChannel = { raw -> SecureChannel(raw, identity, trust, device) },
                    authenticate = { secure -> authenticate(secure) },
                    receiveTransfer = { secure ->
                        var locked = false
                        try {
                            val negotiation = SessionProtocol(secure).acceptIncoming(
                                    available = { activeTransfer.tryLock().also { locked = it } },
                                    onPrepared = {
                                        val id = authenticatedIds[secure]
                                            ?: throw IllegalStateException("Missing authenticated peer")
                                        val slot = PreparedReceiver(secure, CompletableDeferred())
                                        if (prepared.putIfAbsent(id, slot) != null) {
                                            SessionProtocol(secure).write(com.fastdrop.session.SessionMessage.Busy)
                                            return@acceptIncoming
                                        }
                                        FastDropDiagnostics.debug("[Session] prepared peer=$id")
                                        try { slot.finished.await() }
                                        finally { prepared.remove(id, slot) }
                                    }
                                )
                            if (negotiation != IncomingSessionResult.SEND_READY)
                                return@PersistentReceiverServer negotiation == IncomingSessionResult.PREPARED_DONE
                            FastDropDiagnostics.debug("[Session] READY; transfer started")
                            var fileName = "file"
                            val success = transfers.receiveFile(secure,
                                onOfferReceived = { offer ->
                                    fileName = transfers.sanitizeFilename(offer.name)
                                    setStatus(DesktopStatus.Receiving(fileName, 0, offer.size))
                                    DesktopIncomingFileDestination(File(
                                        System.getProperty("user.home"), "Downloads/FastDrop/$fileName"))
                                }, onProgress = { current, total ->
                                    setStatus(DesktopStatus.Receiving(fileName, current, total))
                                })
                            setStatus(if (success) DesktopStatus.Completed(fileName)
                                      else DesktopStatus.Error("Transfer failed"))
                            FastDropDiagnostics.debug("[Session] transfer completed success=$success")
                            success
                        } finally {
                            if (locked) activeTransfer.unlock()
                        }
                    },
                    closeSecureChannel = { authenticatedIds.remove(it); it.close() },
                    closeRawConnection = { it.close() },
                    stopHosting = { transport.stop() },
                    onListening = {
                        if (mutableState.value.status is DesktopStatus.Completed ||
                            mutableState.value.status is DesktopStatus.Rejected ||
                            mutableState.value.status is DesktopStatus.IntegrityFailed ||
                            mutableState.value.status is DesktopStatus.ConnectionLost ||
                            mutableState.value.status is DesktopStatus.Error) {
                            scope.launch { delay(3000); setStatus(DesktopStatus.Ready) }
                        } else if (!activeTransfer.isLocked) setStatus(DesktopStatus.Ready)
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

    fun send(file: File, deviceId: String?) {
        require(file.isFile)
        scope.launch {
            if (!activeTransfer.tryLock()) { setStatus(DesktopStatus.Busy); return@launch }
            val peer = discoveredPeers.firstOrNull { it.deviceId == deviceId && deviceId != null }
            if (peer == null || peer.sessionVersion != SessionProtocol.VERSION || peer.addresses.isEmpty()) {
                activeTransfer.unlock()
                setStatus(DesktopStatus.Error("Peer does not support session version 1"))
                return@launch
            }
            val outgoingTransport = ManualLanTransport(peer.port)
            var raw: com.fastdrop.transport.Connection? = null
            var secure: SecureChannel? = null
            var slot: PreparedReceiver? = null
            try {
                setStatus(DesktopStatus.Connecting(peer.displayName ?: "FastDrop device"))
                slot = prepared.remove(deviceId)
                if (slot == null) {
                    try {
                        raw = withTimeout(4_000) { outgoingTransport.connect(Peer(deviceId!!,
                            peer.displayName ?: "FastDrop device", TransportType.LAN, peer.addresses.first())) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        FastDropDiagnostics.debug("[Session] direct LAN unavailable peer=${peer.displayName}")
                    }
                }
                if (slot == null && raw == null && trust.getPeer(deviceId!!) != null) {
                    slot = withTimeoutOrNull(35_000) {
                        var found: PreparedReceiver? = null
                        while (found == null) {
                            found = prepared.remove(deviceId)
                            if (found == null) delay(200)
                        }
                        found
                    }
                }
                if (slot != null) {
                    secure = slot.channel
                } else {
                    if (raw == null) throw IllegalStateException("Peer is not reachable")
                    secure = SecureChannel(raw, identity, trust, device)
                    if (!authenticate(secure)) { setStatus(DesktopStatus.Rejected); return@launch }
                    if (authenticatedIds[secure] != deviceId)
                        throw IllegalStateException("Discovered peer identity mismatch")
                }
                FastDropDiagnostics.debug("[Session] SEND_INTENT peer=${peer.displayName}")
                val ready = withTimeout(10_000) {
                    if (slot != null) SessionProtocol(secure).beginPreparedSend()
                    else SessionProtocol(secure).requestSend()
                }
                if (!ready) {
                    FastDropDiagnostics.debug("[Session] BUSY")
                    setStatus(DesktopStatus.Busy)
                    return@launch
                }
                FastDropDiagnostics.debug("[Session] READY; transfer started")
                var outcome = TransferOutcome.FAILED
                transfers.sendFile(secure, DesktopTransferFileSource(file),
                    onProgress = { current, total -> setStatus(DesktopStatus.Sending(file.name, current, total)) },
                    onOutcome = { outcome = it })
                setStatus(when (outcome) {
                    TransferOutcome.SUCCESS -> DesktopStatus.Completed(file.name)
                    TransferOutcome.REJECTED -> DesktopStatus.Rejected
                    TransferOutcome.HASH_MISMATCH -> DesktopStatus.IntegrityFailed
                    TransferOutcome.CONNECTION_LOST -> DesktopStatus.ConnectionLost
                    TransferOutcome.FAILED -> DesktopStatus.Error("Transfer failed")
                })
                FastDropDiagnostics.debug("[Session] transfer completed outcome=$outcome")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                setStatus(DesktopStatus.ConnectionLost)
                if (FastDropDiagnostics.enabled) error.printStackTrace()
            } finally {
                withContext(NonCancellable) {
                    if (slot != null) slot.finished.complete(Unit)
                    else runCatching { (secure ?: raw)?.close() }
                    if (secure != null) authenticatedIds.remove(secure)
                    runCatching { outgoingTransport.stop() }
                }
                activeTransfer.unlock()
                FastDropDiagnostics.debug("[Session] disconnected")
            }
        }
    }

    private suspend fun authenticate(secure: SecureChannel): Boolean {
        val verification = secure.handshake()
        val name = when (verification) {
            is PeerVerification.NewPeer -> verification.friendlyName
            is PeerVerification.TrustedPeer -> verification.friendlyName
        }
        setStatus(DesktopStatus.Authenticating(name))
        if (verification is PeerVerification.NewPeer) {
            val decision = CompletableDeferred<Boolean>()
            pendingSas = decision
            mutableState.value = mutableState.value.copy(sas = SasRequest(name, verification.sas))
            val accepted = try { decision.await() } finally {
                pendingSas = null
                mutableState.value = mutableState.value.copy(sas = null)
            }
            if (!accepted) return false
            secure.confirmPeer(verification)
            mutableState.value = mutableState.value.copy(peers = mutableState.value.peers.map { peer ->
                if (peer.deviceId == verification.deviceId) peer.copy(trusted = true) else peer
            })
        }
        authenticatedIds[secure] = when (verification) {
            is PeerVerification.NewPeer -> verification.deviceId
            is PeerVerification.TrustedPeer -> verification.deviceId
        }
        FastDropDiagnostics.debug("[Session] connected peer=$name")
        return true
    }

    fun confirmSas(accept: Boolean) { pendingSas?.complete(accept) }

    suspend fun stop() {
        if (!running) return
        running = false
        pendingSas?.complete(false)
        prepared.values.forEach { it.finished.complete(Unit) }
        prepared.clear()
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
