package com.fastdrop.security

import dev.whyoleg.cryptography.algorithms.EdDSA
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.CryptographyProvider

/**
 * Représente l'identité persistante de l'appareil.
 */
data class DeviceIdentity(
    val keyPair: EdDSA.KeyPair
)

/**
 * Interface pour le stockage de l'identité persistante (Ed25519) de cet appareil.
 */
interface IdentityStore {
    /**
     * Retourne l'identité persistante.
     * Si aucune identité n'existe, elle doit être générée et sauvegardée.
     * Si le stockage est corrompu, lève une exception (ne génère pas silencieusement).
     */
    suspend fun getOrGenerateIdentity(): DeviceIdentity
}

/**
 * Fournit les métadonnées de l'appareil (ex: le nom de l'appareil).
 * Ne doit PAS être utilisé pour l'authentification cryptographique.
 */
interface DeviceInfoProvider {
    suspend fun getDeviceName(): String
}

/**
 * Informations sur un appareil de confiance.
 */
data class TrustedPeer(
    val deviceId: String,       // L'identifiant (SHA-256 de la clé publique canonique)
    val publicKey: ByteArray,   // Clé publique Ed25519 en format RAW
    val friendlyName: String,   // Nom de l'appareil
    val firstSeen: Long,        // Timestamp de la première rencontre
    val lastSeen: Long          // Timestamp de la dernière connexion réussie
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as TrustedPeer
        return deviceId == other.deviceId && publicKey.contentEquals(other.publicKey) && friendlyName == other.friendlyName && firstSeen == other.firstSeen && lastSeen == other.lastSeen
    }
    
    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + friendlyName.hashCode()
        result = 31 * result + firstSeen.hashCode()
        result = 31 * result + lastSeen.hashCode()
        return result
    }
}

/**
 * Interface pour stocker et vérifier les appareils avec lesquels l'utilisateur
 * a déjà établi une connexion sécurisée (SAS validé).
 */
interface TrustedPeerStore {
    /**
     * Récupère un appareil de confiance à partir de son fingerprint (deviceId).
     */
    suspend fun getPeer(deviceId: String): TrustedPeer?
    
    /**
     * Sauvegarde un nouvel appareil de confiance ou met à jour le lastSeen.
     */
    suspend fun savePeer(peer: TrustedPeer)
}

class IdentityStoreCorruptedException(message: String) : Exception(message)

/**
 * Utilitaire pour calculer le fingerprint (SHA-256) d'une clé publique Ed25519 RAW.
 * Retourne le hash formaté en hexadécimal (ex: 7A:91:4F...).
 */
suspend fun calculateFingerprint(publicKeyBytes: ByteArray, provider: CryptographyProvider = CryptographyProvider.Default): String {
    val hash = provider.get(SHA256).hasher().hash(publicKeyBytes)
    return hash.joinToString(":") {
        it.toUByte().toString(16).padStart(2, '0').uppercase()
    }
}

sealed interface PeerVerification {
    data class NewPeer(
        val deviceId: String,
        val friendlyName: String,
        val fingerprint: String,
        val sas: String,
        val publicKeyRaw: ByteArray
    ) : PeerVerification {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as NewPeer
            return deviceId == other.deviceId && friendlyName == other.friendlyName && fingerprint == other.fingerprint && sas == other.sas && publicKeyRaw.contentEquals(other.publicKeyRaw)
        }
        override fun hashCode(): Int {
            var result = deviceId.hashCode()
            result = 31 * result + friendlyName.hashCode()
            result = 31 * result + fingerprint.hashCode()
            result = 31 * result + sas.hashCode()
            result = 31 * result + publicKeyRaw.contentHashCode()
            return result
        }
    }

    data class TrustedPeer(
        val deviceId: String,
        val friendlyName: String,
        val publicKeyRaw: ByteArray
    ) : PeerVerification {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as TrustedPeer
            return deviceId == other.deviceId && friendlyName == other.friendlyName && publicKeyRaw.contentEquals(other.publicKeyRaw)
        }
        override fun hashCode(): Int {
            var result = deviceId.hashCode()
            result = 31 * result + friendlyName.hashCode()
            result = 31 * result + publicKeyRaw.contentHashCode()
            return result
        }
    }
}
