package com.fastdrop.security

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class AndroidTrustedPeerStore(
    private val context: Context
) : TrustedPeerStore {
    private val prefs = context.getSharedPreferences("fastdrop_peers", Context.MODE_PRIVATE)

    override suspend fun getPeer(deviceId: String): TrustedPeer? {
        return withContext(Dispatchers.IO) {
            val jsonStr = prefs.getString("peers", "[]")
            val array = JSONArray(jsonStr!!)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                if (obj.getString("deviceId") == deviceId) {
                    val pubB64 = obj.optString("publicKey", "")
                    val pub = if (pubB64.isNotEmpty()) Base64.decode(pubB64, Base64.NO_WRAP) else ByteArray(0)
                    return@withContext TrustedPeer(
                        deviceId = obj.getString("deviceId"),
                        publicKey = pub,
                        friendlyName = obj.optString("friendlyName", "Unknown"),
                        firstSeen = obj.optLong("firstSeen", 0L),
                        lastSeen = obj.optLong("lastSeen", 0L)
                    )
                }
            }
            null
        }
    }

    override suspend fun savePeer(peer: TrustedPeer) {
        withContext(Dispatchers.IO) {
            val jsonStr = prefs.getString("peers", "[]")
            val array = JSONArray(jsonStr!!)
            
            // Remove existing if any
            val newArray = JSONArray()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                if (obj.getString("deviceId") != peer.deviceId) {
                    newArray.put(obj)
                }
            }
            
            val newObj = JSONObject().apply {
                put("deviceId", peer.deviceId)
                put("publicKey", Base64.encodeToString(peer.publicKey, Base64.NO_WRAP))
                put("friendlyName", peer.friendlyName)
                put("firstSeen", peer.firstSeen)
                put("lastSeen", peer.lastSeen)
            }
            newArray.put(newObj)
            
            prefs.edit().putString("peers", newArray.toString()).apply()
        }
    }
}
