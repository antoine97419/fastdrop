package com.fastdrop.transport

import com.fastdrop.core.Peer
import kotlinx.coroutines.flow.Flow

/**
 * Représente une connexion active (socket) avec un appareil distant.
 */
interface Connection {
    val peer: Peer
    
    /** Lit des données depuis la connexion */
    suspend fun read(buffer: ByteArray): Int
    
    /** Écrit des données dans la connexion */
    suspend fun write(data: ByteArray, offset: Int = 0, length: Int = data.size)
    
    /** Ferme la connexion */
    suspend fun close()
}

/**
 * Abstraction pour un type de réseau (LAN, Wi-Fi Direct, etc.)
 */
interface Transport {
    /** Découvre les appareils disponibles sur ce transport */
    suspend fun discover(): Flow<List<Peer>>
    
    /** Initie une connexion vers un appareil distant */
    suspend fun connect(peer: Peer): Connection
    
    /** Démarre l'écoute des connexions entrantes sur ce transport */
    suspend fun startHosting(): Flow<Connection>
    
    /** Arrête la découverte et l'écoute */
    suspend fun stop()
}
