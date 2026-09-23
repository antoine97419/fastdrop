package com.fastdrop.security

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EdDSA

class TestIdentityStore(private val provider: CryptographyProvider = CryptographyProvider.Default) : IdentityStore {
    private val eddsa = provider.get(EdDSA)
    private var identity: DeviceIdentity? = null

    override suspend fun getOrGenerateIdentity(): DeviceIdentity {
        if (identity == null) {
            val keyPair = eddsa.keyPairGenerator(EdDSA.Curve.Ed25519).generateKey()
            identity = DeviceIdentity(keyPair)
        }
        return identity!!
    }
}

class TestDeviceInfoProvider(private val name: String) : DeviceInfoProvider {
    override suspend fun getDeviceName(): String = name
}

class TestTrustedPeerStore : TrustedPeerStore {
    private val map = mutableMapOf<String, TrustedPeer>()
    override suspend fun getPeer(deviceId: String): TrustedPeer? = map[deviceId]
    override suspend fun savePeer(peer: TrustedPeer) { map[peer.deviceId] = peer }
}
