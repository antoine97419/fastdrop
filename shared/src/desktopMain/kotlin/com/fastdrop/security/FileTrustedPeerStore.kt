package com.fastdrop.security

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@Serializable
private data class TrustedPeerData(
    val deviceId: String,
    val publicKeyRawBase64: String,
    val friendlyName: String,
    val firstSeen: Long,
    val lastSeen: Long
)

@Serializable
private data class TrustStoreData(
    val peers: List<TrustedPeerData> = emptyList()
)

@OptIn(ExperimentalEncodingApi::class)
class FileTrustedPeerStore(
    private val file: File
) : TrustedPeerStore {
    private val json = Json { ignoreUnknownKeys = true }

    private fun readData(): TrustStoreData {
        if (!file.exists()) return TrustStoreData()
        return try {
            json.decodeFromString<TrustStoreData>(file.readText())
        } catch (e: Exception) {
            TrustStoreData() // Or throw depending on requirements, but generally we can reset or backup.
        }
    }

    private fun writeData(data: TrustStoreData) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(data))
    }

    override suspend fun getPeer(deviceId: String): TrustedPeer? {
        val data = readData()
        val peerData = data.peers.find { it.deviceId == deviceId } ?: return null
        return try {
            TrustedPeer(
                deviceId = peerData.deviceId,
                publicKey = Base64.decode(peerData.publicKeyRawBase64),
                friendlyName = peerData.friendlyName,
                firstSeen = peerData.firstSeen,
                lastSeen = peerData.lastSeen
            )
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun savePeer(peer: TrustedPeer) {
        val data = readData()
        val existingIndex = data.peers.indexOfFirst { it.deviceId == peer.deviceId }
        
        val peerData = TrustedPeerData(
            deviceId = peer.deviceId,
            publicKeyRawBase64 = Base64.encode(peer.publicKey),
            friendlyName = peer.friendlyName,
            firstSeen = peer.firstSeen,
            lastSeen = peer.lastSeen
        )
        
        val newList = data.peers.toMutableList()
        if (existingIndex >= 0) {
            newList[existingIndex] = peerData
        } else {
            newList.add(peerData)
        }
        writeData(TrustStoreData(newList))
    }
}
