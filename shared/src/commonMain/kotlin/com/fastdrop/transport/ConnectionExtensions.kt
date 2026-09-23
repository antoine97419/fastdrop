package com.fastdrop.transport

/**
 * Lit exactement [length] octets depuis la connexion.
 * Suspend jusqu'à ce que le buffer soit rempli ou que la connexion soit fermée.
 * @return ByteArray contenant les données, ou null si la connexion est fermée prématurément.
 */
suspend fun Connection.readExactly(length: Int): ByteArray? {
    val result = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val tempBuffer = ByteArray(length - offset)
        val read = this.read(tempBuffer)
        if (read <= 0) return null // EOF
        tempBuffer.copyInto(result, offset, 0, read)
        offset += read
    }
    return result
}
