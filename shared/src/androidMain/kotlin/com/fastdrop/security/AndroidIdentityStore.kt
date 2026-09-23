package com.fastdrop.security

import android.content.Context
import android.util.Base64
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.CryptographyProviderApi
import dev.whyoleg.cryptography.algorithms.EdDSA
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(CryptographyProviderApi::class)
class AndroidIdentityStore(
    private val context: Context,
    private val cryptoProvider: CryptographyProvider
) : IdentityStore {
    private val prefs = context.getSharedPreferences("fastdrop_identity", Context.MODE_PRIVATE)

    override suspend fun getOrGenerateIdentity(): DeviceIdentity {
        return withContext(Dispatchers.IO) {
            val pubB64 = prefs.getString("public_key", null)
            val privB64 = prefs.getString("private_key", null)

            val edDsa = cryptoProvider.get(EdDSA)
            if (pubB64 != null && privB64 != null) {
                val pub = Base64.decode(pubB64, Base64.NO_WRAP)
                val priv = Base64.decode(privB64, Base64.NO_WRAP)
                
                val pubKey = edDsa.publicKeyDecoder(EdDSA.Curve.Ed25519).decodeFromByteArray(EdDSA.PublicKey.Format.RAW, pub)
                val privKey = edDsa.privateKeyDecoder(EdDSA.Curve.Ed25519).decodeFromByteArray(EdDSA.PrivateKey.Format.RAW, priv)
                
                val kp = object : EdDSA.KeyPair {
                    override val publicKey = pubKey
                    override val privateKey = privKey
                }
                DeviceIdentity(kp)
            } else {
                val kp = edDsa.keyPairGenerator(EdDSA.Curve.Ed25519).generateKey()
                val pub = kp.publicKey.encodeToByteArray(EdDSA.PublicKey.Format.RAW)
                val priv = kp.privateKey.encodeToByteArray(EdDSA.PrivateKey.Format.RAW)
                
                prefs.edit()
                    .putString("public_key", Base64.encodeToString(pub, Base64.NO_WRAP))
                    .putString("private_key", Base64.encodeToString(priv, Base64.NO_WRAP))
                    .apply()
                    
                DeviceIdentity(kp)
            }
        }
    }
}
