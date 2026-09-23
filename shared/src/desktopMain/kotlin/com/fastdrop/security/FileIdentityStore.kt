package com.fastdrop.security

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.CryptographyProviderApi
import dev.whyoleg.cryptography.algorithms.EdDSA
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@Serializable
private data class IdentityData(
    val privateKeyRawBase64: String,
    val publicKeyRawBase64: String
)

@OptIn(ExperimentalEncodingApi::class, CryptographyProviderApi::class)
class FileIdentityStore(
    private val file: File,
    private val provider: CryptographyProvider = CryptographyProvider.Default
) : IdentityStore {
    private val eddsa = provider.get(EdDSA)

    override suspend fun getOrGenerateIdentity(): DeviceIdentity {
        if (file.exists()) {
            val content = file.readText()
            val data = try {
                Json.decodeFromString<IdentityData>(content)
            } catch (e: Exception) {
                throw IdentityStoreCorruptedException("Le fichier d'identité est corrompu ou illisible.")
            }
            
            try {
                val privBytes = Base64.decode(data.privateKeyRawBase64)
                val pubBytes = Base64.decode(data.publicKeyRawBase64)
                val pub = eddsa.publicKeyDecoder(EdDSA.Curve.Ed25519).decodeFromByteArray(EdDSA.PublicKey.Format.RAW, pubBytes)
                val priv = eddsa.privateKeyDecoder(EdDSA.Curve.Ed25519).decodeFromByteArray(EdDSA.PrivateKey.Format.RAW, privBytes)
                return DeviceIdentity(object : EdDSA.KeyPair {
                    override val publicKey = pub
                    override val privateKey = priv
                })
            } catch (e: Exception) {
                throw IdentityStoreCorruptedException("Impossible de décoder les clés Ed25519 existantes.")
            }
        }
        
        // Generation
        val keyPair = eddsa.keyPairGenerator(EdDSA.Curve.Ed25519).generateKey()
        val pubBytes = keyPair.publicKey.encodeToByteArray(EdDSA.PublicKey.Format.RAW)
        val privBytes = keyPair.privateKey.encodeToByteArray(EdDSA.PrivateKey.Format.RAW)
        
        val data = IdentityData(
            privateKeyRawBase64 = Base64.encode(privBytes),
            publicKeyRawBase64 = Base64.encode(pubBytes)
        )
        
        file.parentFile?.mkdirs()
        file.writeText(Json.encodeToString(data))
        
        return DeviceIdentity(keyPair)
    }
}
