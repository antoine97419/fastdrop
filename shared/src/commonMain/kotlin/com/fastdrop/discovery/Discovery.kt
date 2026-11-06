package com.fastdrop.discovery

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

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
    val capabilities: Set<String> = emptySet()
)

interface DiscoveryProvider {
    val peers: Flow<List<DiscoveredPeer>>

    suspend fun start()
    suspend fun stop()
}

class DiscoveryManager(
    private val providers: List<DiscoveryProvider>
) {
    val peers: Flow<List<DiscoveredPeer>> = kotlinx.coroutines.flow.channelFlow {
        val latest = Array(providers.size) { emptyList<DiscoveredPeer>() }
        providers.forEachIndexed { index, provider ->
            launch {
                provider.peers.collect { list ->
                    latest[index] = list
                    send(latest.flatMap { it }
                        .groupBy { it.deviceId ?: it.discoveryId }
                        .values.map { matches ->
                            matches.firstOrNull { it.isFastDropService } ?: matches.first()
                        })
                }
            }
        }
    }

    suspend fun startAll() {
        providers.forEach { it.start() }
    }

    suspend fun stopAll() {
        providers.forEach { it.stop() }
    }
}
