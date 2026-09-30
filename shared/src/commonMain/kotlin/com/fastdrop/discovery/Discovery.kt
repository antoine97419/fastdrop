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
    val protocolVersion: Int = 1
)

interface DiscoveryProvider {
    val peers: Flow<List<DiscoveredPeer>>

    suspend fun start()
    suspend fun stop()
}

class DiscoveryManager(
    private val providers: List<DiscoveryProvider>
) {
    // Basic aggregation
    val peers: Flow<List<DiscoveredPeer>> = kotlinx.coroutines.flow.channelFlow {
        // Collect from all providers and merge/deduplicate
        val currentPeers = mutableMapOf<String, DiscoveredPeer>()
        
        // For simplicity in MVP, we can just launch collectors for each provider
        providers.forEach { provider ->
            launch {
                provider.peers.collect { list ->
                    // Merge logic: we can just replace them based on discoveryId
                    // Or keep it simple: just take the latest from the provider
                    // (A more advanced merge logic is needed for multi-provider)
                    // For now, just emit the list from the single provider.
                    send(list)
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
