package com.fastdrop.security

/**
 * Représente une identité persistante d'un appareil.
 */
data class PeerIdentity(
    val identityPublicKey: ByteArray,
    val fingerprint: String,
    val friendlyName: String?,
    val firstSeen: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as PeerIdentity

        if (!identityPublicKey.contentEquals(other.identityPublicKey)) return false
        if (fingerprint != other.fingerprint) return false
        if (friendlyName != other.friendlyName) return false
        if (firstSeen != other.firstSeen) return false

        return true
    }

    override fun hashCode(): Int {
        var result = identityPublicKey.contentHashCode()
        result = 31 * result + fingerprint.hashCode()
        result = 31 * result + (friendlyName?.hashCode() ?: 0)
        result = 31 * result + firstSeen.hashCode()
        return result
    }
}

/**
 * Gère l'identité persistante de cet appareil (Ed25519).
 */
interface IdentityStore {
    suspend fun getIdentityPublicKey(): ByteArray
    suspend fun getIdentityPrivateKey(): ByteArray
    suspend fun generateNewIdentity()
    suspend fun hasIdentity(): Boolean
}

/**
 * Gère les identités connues des pairs (TrustStore).
 */
interface TrustedPeerStore {
    suspend fun getTrustedPeer(fingerprint: String): PeerIdentity?
    suspend fun addTrustedPeer(identity: PeerIdentity)
    suspend fun removeTrustedPeer(fingerprint: String)
    suspend fun getAllTrustedPeers(): List<PeerIdentity>
}
