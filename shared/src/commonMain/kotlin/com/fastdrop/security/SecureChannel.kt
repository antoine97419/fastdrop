package com.fastdrop.security

import com.fastdrop.core.Peer
import com.fastdrop.transport.Connection

/**
 * Une connexion enveloppée qui chiffre de manière transparente (E2E) toutes
 * les données lues et écrites sur le réseau physique potentiellement hostile.
 */
class SecureChannel(
    private val underlyingConnection: Connection,
    // clés symétriques dérivées de l'échange ECDH
) : Connection {
    
    override val peer: Peer
        get() = underlyingConnection.peer

    override suspend fun read(buffer: ByteArray): Int {
        // 1. Lire depuis underlyingConnection
        // 2. Déchiffrer via AEAD (ex: ChaCha20-Poly1305)
        // 3. Remplir le buffer
        return underlyingConnection.read(buffer) // TODO: Implementer le déchiffrement
    }

    override suspend fun write(data: ByteArray, offset: Int, length: Int) {
        // 1. Chiffrer 'data'
        // 2. Écrire le buffer chiffré dans underlyingConnection
        underlyingConnection.write(data, offset, length) // TODO: Implementer le chiffrement
    }

    override suspend fun close() {
        underlyingConnection.close()
    }
}
