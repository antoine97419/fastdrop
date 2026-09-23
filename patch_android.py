import sys

with open("shared/src/androidMain/kotlin/com/fastdrop/security/AndroidIdentityStore.kt", "r") as f:
    text = f.read()

text = text.replace("Identity(kp, pub)", "DeviceIdentity(kp)")
text = text.replace("override suspend fun getOrGenerateIdentity(): Identity {", "override suspend fun getOrGenerateIdentity(): DeviceIdentity {")
with open("shared/src/androidMain/kotlin/com/fastdrop/security/AndroidIdentityStore.kt", "w") as f:
    f.write(text)

with open("shared/src/androidMain/kotlin/com/fastdrop/security/AndroidDeviceInfoProvider.kt", "r") as f:
    text = f.read()

text = text.replace("override fun getDeviceName(): String {", "override suspend fun getDeviceName(): String {")
with open("shared/src/androidMain/kotlin/com/fastdrop/security/AndroidDeviceInfoProvider.kt", "w") as f:
    f.write(text)

with open("shared/src/androidMain/kotlin/com/fastdrop/security/AndroidTrustedPeerStore.kt", "r") as f:
    text = f.read()

text = text.replace("override suspend fun getTrustedPeer(deviceId: String): TrustedPeer? {", "override suspend fun getPeer(deviceId: String): TrustedPeer? {")
text = text.replace("override suspend fun addTrustedPeer(peer: TrustedPeer) {", "override suspend fun savePeer(peer: TrustedPeer) {")
text = text.replace("override suspend fun updateLastSeen(deviceId: String, timestamp: Long) {", "suspend fun updateLastSeen(deviceId: String, timestamp: Long) {")

import re
old_ret = """return@withContext TrustedPeer(
                        deviceId = obj.getString("deviceId"),
                        friendlyName = obj.optString("friendlyName", "Unknown"),
                        lastSeen = obj.optLong("lastSeen", 0L)
                    )"""
new_ret = """val pubB64 = obj.optString("publicKey", "")
                    val pub = if (pubB64.isNotEmpty()) android.util.Base64.decode(pubB64, android.util.Base64.NO_WRAP) else ByteArray(0)
                    return@withContext TrustedPeer(
                        deviceId = obj.getString("deviceId"),
                        publicKey = pub,
                        friendlyName = obj.optString("friendlyName", "Unknown"),
                        firstSeen = obj.optLong("firstSeen", 0L),
                        lastSeen = obj.optLong("lastSeen", 0L)
                    )"""
text = text.replace(old_ret, new_ret)

old_put = """                put("deviceId", peer.deviceId)
                put("friendlyName", peer.friendlyName)
                put("lastSeen", peer.lastSeen)"""
new_put = """                put("deviceId", peer.deviceId)
                put("publicKey", android.util.Base64.encodeToString(peer.publicKey, android.util.Base64.NO_WRAP))
                put("friendlyName", peer.friendlyName)
                put("firstSeen", peer.firstSeen)
                put("lastSeen", peer.lastSeen)"""
text = text.replace(old_put, new_put)
text = text.replace("val peer = getTrustedPeer(deviceId)", "val peer = getPeer(deviceId)")
text = text.replace("addTrustedPeer(peer.copy(lastSeen = timestamp))", "savePeer(peer.copy(lastSeen = timestamp))")

with open("shared/src/androidMain/kotlin/com/fastdrop/security/AndroidTrustedPeerStore.kt", "w") as f:
    f.write(text)
