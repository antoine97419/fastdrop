package com.fastdrop.security

internal fun intToBytes(value: Int): ByteArray = ByteArray(4) { i -> (value ushr (24 - i * 8)).toByte() }
internal fun shortToBytes(value: Int): ByteArray = ByteArray(2) { i -> (value ushr (8 - i * 8)).toByte() }

internal fun bytesToInt(bytes: ByteArray, offset: Int = 0): Int {
    return ((bytes[offset].toInt() and 0xFF) shl 24) or
           ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
           ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
           (bytes[offset + 3].toInt() and 0xFF)
}

internal fun bytesToShort(bytes: ByteArray, offset: Int = 0): Int {
    return ((bytes[offset].toInt() and 0xFF) shl 8) or
           (bytes[offset + 1].toInt() and 0xFF)
}
