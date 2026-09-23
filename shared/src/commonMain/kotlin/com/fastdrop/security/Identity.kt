package com.fastdrop.security

import dev.whyoleg.cryptography.algorithms.EdDSA
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.CryptographyAlgorithmId

/**
 * Interface pour le stockage de l'identité persistante (Ed25519) de cet appareil.
 */
interface IdentityStore {
    /**
     * Retourne la clé privée Ed25519 encodée en PKCS8 ou RAW.
     * Si aucune identité n'existe, elle doit être générée et sauvegardée.
     */
    suspend fun getOrGenerateIdentityKey(): ByteArray
    
    /**
     * Retourne le nom d'affichage de cet appareil.
     */
    suspend fun getDeviceName(): String
}

/**
 * Informations sur un appareil de confiance.
 */
data class TrustedPeer(
    val deviceId: String,       // L'identifiant (ex: SHA-256 de la clé publique)
    val publicKey: ByteArray,   // Clé publique Ed25519
    val friendlyName: String,   // Nom de l'appareil
    val lastSeen: Long          // Timestamp
)

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
     * Sauvegarde un nouvel appareil de confiance après validation du SAS par l'utilisateur.
     */
    suspend fun savePeer(peer: TrustedPeer)
}

/**
 * Utilitaire pour calculer le fingerprint (SHA-256) d'une clé publique Ed25519.
 * Retourne le hash formaté en hexadécimal (ex: 7A:91:4F...).
 */
suspend fun calculateFingerprint(publicKeyBytes: ByteArray, provider: CryptographyProvider = CryptographyProvider.Default): String {
    val hash = provider.get(SHA256).hasher().hash(publicKeyBytes)
    return hash.joinToString(":") {
        it.toUByte().toString(16).padStart(2, '0').uppercase()
    }
}
