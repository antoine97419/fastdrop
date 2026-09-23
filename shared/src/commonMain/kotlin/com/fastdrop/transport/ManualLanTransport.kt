package com.fastdrop.transport

import com.fastdrop.core.Peer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Implémentation basique de LanTransport pour des connexions manuelles (IP directe).
 * Ne gère pas la découverte automatique.
 */
class ManualLanTransport(port: Int = 47832) : AbstractLanTransport(port) {
    override val type = com.fastdrop.core.TransportType.LAN
    override suspend fun discover(): Flow<List<Peer>> {
        return emptyFlow() // Pas de découverte automatique
    }
}
