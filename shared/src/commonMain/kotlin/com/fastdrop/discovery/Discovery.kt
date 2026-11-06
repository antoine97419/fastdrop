package com.fastdrop.discovery

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

enum class DiscoveryType {
    MDNS,
    BLE,
    WIFI_DIRECT,
    MANUAL
}

data class DiscoveredPeer(
    val discoveryId: String,
    val displayName: String?,
    val addresses: List<String>,
    val port: Int,
    val source: DiscoveryType,
    val protocolVersion: Int = 1,
    val isFastDropService: Boolean = false,
    val deviceId: String? = null,
    val capabilities: Set<String> = emptySet(),
    val sessionVersion: Int? = null
)

interface DiscoveryProvider {
    val peers: Flow<List<DiscoveredPeer>>

    suspend fun start()
    suspend fun stop()
}

class DiscoveryManager(
    private val providers: List<DiscoveryProvider>,
    private val localDeviceId: String? = null,
    private val onDuplicate: (String) -> Unit = {}
) {
    val peers: Flow<List<DiscoveredPeer>> = combine(providers.map { it.peers }) { latest ->
        mergeDiscoveredPeers(latest.flatMap { it }, localDeviceId, onDuplicate)
    }

    suspend fun startAll() {
        providers.forEach { it.start() }
    }

    suspend fun stopAll() {
        providers.forEach { it.stop() }
    }
}

/** A service name is only a fallback key; public device identity is the logical key. */
fun mergeDiscoveredPeers(
    peers: List<DiscoveredPeer>,
    localDeviceId: String? = null,
    onDuplicate: (String) -> Unit = {}
): List<DiscoveredPeer> = peers
    .filterNot { localDeviceId != null && it.deviceId == localDeviceId }
    .groupBy { it.deviceId ?: it.discoveryId }
    .map { (key, matches) ->
        if (matches.size > 1) onDuplicate(key)
        val preferred = matches.lastOrNull { it.isFastDropService } ?: matches.last()
        preferred.copy(addresses = (preferred.addresses + matches.flatMap { it.addresses }).distinct(),
            capabilities = matches.flatMap { it.capabilities }.toSet())
    }
