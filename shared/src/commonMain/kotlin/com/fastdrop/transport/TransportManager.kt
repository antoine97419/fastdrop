package com.fastdrop.transport

import com.fastdrop.core.Peer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

/**
 * Gère les différents transports disponibles (LAN, WiFi Direct, etc.)
 * et orchestre la découverte et la sélection du meilleur transport.
 */
class TransportManager(
    private val transports: List<Transport>
) {
    private val _availablePeers = MutableStateFlow<List<Peer>>(emptyList())
    val availablePeers: StateFlow<List<Peer>> = _availablePeers

    // TODO: Fusionner les flux de découverte de tous les transports
    suspend fun startDiscovery() {
        // Logique de combinaison des Flows de découverte
        // pour populer _availablePeers
    }

    suspend fun stopDiscovery() {
        transports.forEach { it.stop() }
    }

    /**
     * Choisit le meilleur transport et établit la connexion.
     * Politique : LAN > WiFi Direct > BLE
     */
    suspend fun connectTo(peer: Peer): Connection {
        val transport = transports.find { it.type == peer.transportType }
            ?: throw IllegalStateException("Transport non supporté pour ce Peer")
            
        return transport.connect(peer)
    }
    
    suspend fun startHostingAll(): Flow<Connection> {
        // TODO: Démarrer l'hébergement sur tous les transports et fusionner les flux de connexion entrante
        throw NotImplementedError("Hosting not fully implemented")
    }
}
