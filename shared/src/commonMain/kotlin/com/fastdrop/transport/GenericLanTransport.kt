package com.fastdrop.transport

import com.fastdrop.core.Peer
import com.fastdrop.discovery.DiscoveryProvider
import com.fastdrop.discovery.DiscoveryType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class GenericLanTransport(
    private val discoveryProvider: DiscoveryProvider,
    port: Int = 47832
) : AbstractLanTransport(port) {
    
    override val type = com.fastdrop.core.TransportType.LAN
    
    override suspend fun discover(): Flow<List<Peer>> {
        return discoveryProvider.peers.map { discoveredPeers ->
            discoveredPeers.map { dp ->
                Peer(
                    id = dp.discoveryId,
                    name = dp.displayName ?: "Unknown",
                    transportType = com.fastdrop.core.TransportType.LAN,
                    address = dp.addresses.firstOrNull() ?: ""
                )
            }
        }
    }
}
