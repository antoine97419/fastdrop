package com.fastdrop.android

import android.content.Context
import android.net.Uri
import android.util.Log
import com.fastdrop.FastDropConfig
import com.fastdrop.core.Peer
import com.fastdrop.core.TransportType
import com.fastdrop.discovery.DiscoveredPeer
import com.fastdrop.discovery.DiscoveryManager
import com.fastdrop.security.*
import com.fastdrop.session.SessionProtocol
import com.fastdrop.session.SessionProtocolException
import com.fastdrop.session.SessionGate
import com.fastdrop.transfer.*
import com.fastdrop.transport.ManualLanTransport
import com.fastdrop.transport.AndroidLegacyGoConnector
import dev.whyoleg.cryptography.CryptographyProvider
import kotlinx.coroutines.*

internal sealed interface AndroidSessionStatus {
    data object Ready : AndroidSessionStatus
    data class Connecting(val peer: String) : AndroidSessionStatus
    data class Authenticating(val peer: String) : AndroidSessionStatus
    data class WaitingForApproval(val file: String) : AndroidSessionStatus
    data class Sending(val file: String, val current: Long, val total: Long) : AndroidSessionStatus
    data class Receiving(val file: String, val current: Long, val total: Long) : AndroidSessionStatus
    data class Completed(val file: String) : AndroidSessionStatus
    data object Busy : AndroidSessionStatus
    data object Rejected : AndroidSessionStatus
    data object IntegrityFailed : AndroidSessionStatus
    data object ConnectionLost : AndroidSessionStatus
    data class Error(val message: String) : AndroidSessionStatus
}

/** LAN listener and one-transfer-at-a-time sessions, owned by the Activity lifecycle. */
internal class AndroidFastDropRuntime(
    private val context: Context,
    private val identity: IdentityStore,
    private val trusted: TrustedPeerStore,
    private val device: DeviceInfoProvider,
    private val crypto: CryptographyProvider,
    private val discovery: DiscoveryManager,
    private val onPeers: (List<DiscoveredPeer>, Set<String>) -> Unit,
    private val verifySas: suspend (PeerVerification.NewPeer) -> Boolean,
    private val approveOffer: suspend (ControlMessage.FileOffer, String) -> Boolean,
    private val update: (AndroidSessionStatus) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listener = ManualLanTransport(FastDropConfig.DEFAULT_PORT)
    private val transfer = TransferManager()
    private val active = SessionGate()
    private var listenerJob: Job? = null
    private var discoveryJob: Job? = null
    private val preparedJobs = mutableMapOf<String, Job>()
    @Volatile private var latestPeers: List<DiscoveredPeer> = emptyList()

    fun start() {
        if (listenerJob != null) return
        discoveryJob = scope.launch(Dispatchers.Main.immediate) {
            var backoff = 2_000L
            while (isActive) {
                try {
                    discovery.startAll()
                    backoff = 2_000L
                    discovery.peers.collect { peers ->
                        latestPeers = peers
                        val trustedNames = withContext(Dispatchers.IO) {
                            peers.filter { it.isFastDropService && it.deviceId != null }
                                .filter { trusted.getPeer(it.deviceId!!) != null }
                                .mapNotNull { it.displayName }.toSet()
                        }
                        onPeers(peers, trustedNames)
                        syncPreparedPeers(peers)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.e("FastDrop", "[Discovery][Android] runtime discovery failed; retrying", error)
                    runCatching { discovery.stopAll() }
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(30_000L)
                }
            }
        }
        listenerJob = scope.launch {
            try {
                update(AndroidSessionStatus.Ready)
                listener.startHosting().collect { raw -> launch { receive(raw) } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                update(AndroidSessionStatus.Error(error.message ?: "Listener unavailable"))
            }
        }
    }

    private suspend fun authenticate(raw: com.fastdrop.transport.Connection, expectedId: String? = null, quiet: Boolean = false): Pair<SecureChannel, String> {
        val channel = SecureChannel(raw, identity, trusted, device, crypto)
        val verification = channel.handshake()
        val name = when (verification) {
            is PeerVerification.NewPeer -> verification.friendlyName
            is PeerVerification.TrustedPeer -> verification.friendlyName
        }
        val actualId = when (verification) {
            is PeerVerification.NewPeer -> verification.deviceId
            is PeerVerification.TrustedPeer -> verification.deviceId
        }
        if (expectedId != null && actualId != expectedId) {
            channel.close()
            throw SessionProtocolException("Discovered peer identity mismatch")
        }
        if (!quiet) update(AndroidSessionStatus.Authenticating(name))
        if (verification is PeerVerification.NewPeer) {
            if (!verifySas(verification)) {
                channel.close()
                throw SessionProtocolException("SAS rejected")
            }
            channel.confirmPeer(verification)
            syncPreparedPeers(latestPeers)
        }
        Log.d("FastDrop", "[Session] connected peer=$name")
        return channel to name
    }

    private suspend fun receive(raw: com.fastdrop.transport.Connection) {
        var secure: SecureChannel? = null
        var locked = false
        try {
            val (authenticated, peerName) = authenticate(raw)
            secure = authenticated
            val session = SessionProtocol(secure)
            if (!withTimeout(10_000) { session.acceptSendIntent { active.tryLock().also { locked = it } } }) {
                Log.d("FastDrop", "[Session] BUSY")
                return
            }
            Log.d("FastDrop", "[Session] READY; transfer started")
            var fileName = "file"
            var rejected = false
            val success = transfer.receiveFile(secure, onOfferReceived = { offer ->
                fileName = transfer.sanitizeFilename(offer.name)
                if (approveOffer(offer, peerName)) {
                    update(AndroidSessionStatus.Receiving(fileName, 0, offer.size))
                    AndroidIncomingFileDestination(context, fileName)
                } else { rejected = true; null }
            }, onProgress = { current, total ->
                update(AndroidSessionStatus.Receiving(fileName, current, total))
            })
            update(when {
                success -> AndroidSessionStatus.Completed(fileName)
                rejected -> AndroidSessionStatus.Rejected
                else -> AndroidSessionStatus.IntegrityFailed
            })
            Log.d("FastDrop", "[Session] transfer completed success=$success")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e("FastDrop", "[Session] receive failed", error)
            update(if (error is SessionProtocolException && error.message == "SAS rejected")
                AndroidSessionStatus.Rejected else AndroidSessionStatus.ConnectionLost)
        } finally {
            if (locked) active.unlock()
            withContext(NonCancellable) { runCatching { (secure ?: raw).close() } }
            Log.d("FastDrop", "[Session] disconnected")
        }
    }

    private suspend fun connectPeer(peer: DiscoveredPeer, transport: ManualLanTransport): com.fastdrop.transport.Connection {
        val address = peer.addresses.first()
        val target = Peer(peer.deviceId ?: peer.discoveryId, peer.displayName ?: "FastDrop device",
            TransportType.LAN, address)
        return try {
            withTimeout(4_000) { transport.connect(target) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (lanFailure: Exception) {
            if (address != "192.168.137.1") throw lanFailure
            Log.d("FastDrop", "[Session] LAN unavailable; connecting through Legacy GO")
            withTimeout(30_000) { AndroidLegacyGoConnector(context).connect() }
        }
    }

    private fun syncPreparedPeers(peers: List<DiscoveredPeer>) {
        val eligible = peers.filter { it.source == com.fastdrop.discovery.DiscoveryType.MDNS &&
            it.isFastDropService && it.sessionVersion == SessionProtocol.VERSION &&
            it.deviceId != null && it.addresses.isNotEmpty() }
            .associateBy { it.deviceId!! }
        synchronized(preparedJobs) {
            preparedJobs.keys.filter { it !in eligible }.forEach { id -> preparedJobs.remove(id)?.cancel() }
            eligible.forEach { (id, peer) ->
                if (preparedJobs[id]?.isActive != true)
                    preparedJobs[id] = scope.launch { maintainPreparedReceiver(peer) }
            }
        }
    }

    private suspend fun maintainPreparedReceiver(peer: DiscoveredPeer) {
        val id = peer.deviceId ?: return
        var backoff = 2_000L
        while (currentCoroutineContext().isActive) {
            if (trusted.getPeer(id) == null) return
            val transport = ManualLanTransport(peer.port)
            var raw: com.fastdrop.transport.Connection? = null
            var secure: SecureChannel? = null
            var locked = false
            try {
                raw = connectPeer(peer, transport)
                val (channel, name) = authenticate(raw, id, quiet = true)
                secure = channel
                Log.d("FastDrop", "[Session] connected peer=$name; receive ready")
                if (SessionProtocol(channel).offerReceive { active.tryLock().also { locked = it } }) {
                    Log.d("FastDrop", "[Session] READY; transfer started")
                    var fileName = "file"
                    var rejected = false
                    val success = transfer.receiveFile(channel, onOfferReceived = { offer ->
                        fileName = transfer.sanitizeFilename(offer.name)
                        if (approveOffer(offer, name)) {
                            update(AndroidSessionStatus.Receiving(fileName, 0, offer.size))
                            AndroidIncomingFileDestination(context, fileName)
                        } else { rejected = true; null }
                    }, onProgress = { current, total ->
                        update(AndroidSessionStatus.Receiving(fileName, current, total))
                    })
                    update(when {
                        success -> AndroidSessionStatus.Completed(fileName)
                        rejected -> AndroidSessionStatus.Rejected
                        else -> AndroidSessionStatus.IntegrityFailed
                    })
                    Log.d("FastDrop", "[Session] transfer completed success=$success")
                    backoff = 2_000L
                } else Log.d("FastDrop", "[Session] BUSY")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.d("FastDrop", "[Session] prepared receiver disconnected peer=$id", error)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            } finally {
                if (locked) active.unlock()
                withContext(NonCancellable) {
                    runCatching { (secure ?: raw)?.close() }
                    runCatching { transport.stop() }
                }
                Log.d("FastDrop", "[Session] disconnected peer=$id")
            }
            delay(backoff)
        }
    }

    fun send(uri: Uri, peer: DiscoveredPeer) {
        scope.launch {
            if (!active.tryLock()) { update(AndroidSessionStatus.Busy); return@launch }
            val address = peer.addresses.firstOrNull()
            if (peer.sessionVersion != SessionProtocol.VERSION || address == null) {
                active.unlock()
                update(AndroidSessionStatus.Error("Peer does not support session version 1"))
                return@launch
            }
            val connectionTransport = ManualLanTransport(peer.port)
            var raw: com.fastdrop.transport.Connection? = null
            var secure: SecureChannel? = null
            try {
                update(AndroidSessionStatus.Connecting(peer.displayName ?: "FastDrop device"))
                val target = Peer(peer.deviceId ?: peer.discoveryId, peer.displayName ?: "FastDrop device",
                    TransportType.LAN, address)
                raw = connectPeer(peer, connectionTransport)
                secure = authenticate(raw, peer.deviceId).first
                val session = SessionProtocol(secure)
                Log.d("FastDrop", "[Session] SEND_INTENT")
                if (!withTimeout(10_000) { session.requestSend() }) {
                    Log.d("FastDrop", "[Session] BUSY")
                    update(AndroidSessionStatus.Busy)
                    return@launch
                }
                Log.d("FastDrop", "[Session] READY; transfer started")
                val source = AndroidTransferFileSource(context, uri)
                update(AndroidSessionStatus.WaitingForApproval(source.name))
                var outcome = TransferOutcome.FAILED
                transfer.sendFile(secure, source,
                    onProgress = { current, total ->
                        update(AndroidSessionStatus.Sending(source.name, current, total))
                    }, onOutcome = { outcome = it })
                update(when (outcome) {
                    TransferOutcome.SUCCESS -> AndroidSessionStatus.Completed(source.name)
                    TransferOutcome.REJECTED -> AndroidSessionStatus.Rejected
                    TransferOutcome.HASH_MISMATCH -> AndroidSessionStatus.IntegrityFailed
                    TransferOutcome.CONNECTION_LOST -> AndroidSessionStatus.ConnectionLost
                    else -> AndroidSessionStatus.Error("Transfer failed")
                })
                Log.d("FastDrop", "[Session] transfer completed outcome=$outcome")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("FastDrop", "[Session] send failed", error)
                update(when {
                    error is SessionProtocolException && error.message == "SAS rejected" -> AndroidSessionStatus.Rejected
                    error is java.io.FileNotFoundException || error is SecurityException ->
                        AndroidSessionStatus.Error("File is unavailable")
                    else -> AndroidSessionStatus.ConnectionLost
                })
            } finally {
                withContext(NonCancellable) {
                    runCatching { (secure ?: raw)?.close() }
                    runCatching { connectionTransport.stop() }
                }
                active.unlock()
                Log.d("FastDrop", "[Session] disconnected")
            }
        }
    }

    suspend fun stop() {
        synchronized(preparedJobs) { preparedJobs.values.forEach { it.cancel() }; preparedJobs.clear() }
        discoveryJob?.cancel()
        discovery.stopAll()
        listener.stop()
        scope.cancel()
    }
}
