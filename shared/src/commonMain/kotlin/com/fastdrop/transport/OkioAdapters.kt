package com.fastdrop.transport

import okio.Buffer
import okio.Sink
import okio.Source
import okio.Timeout

/**
 * Adapte l'interface [Connection] en un [Source] Okio.
 */
class ConnectionSource(private val connection: Connection) : Source {
    override fun close() {
        // La fermeture de la source ne ferme pas nécessairement la connexion globalement,
        // mais pour simplifier, on ne fait rien ici. (Géré par TransferManager)
    }

    override fun read(sink: Buffer, byteCount: Long): Long {
        throw UnsupportedOperationException("Call suspendRead instead")
    }
    
    suspend fun suspendRead(sink: Buffer, byteCount: Long): Long {
        if (byteCount == 0L) return 0L
        val bufferSize = minOf(byteCount, 8192).toInt()
        val tempBuffer = ByteArray(bufferSize)
        
        val bytesRead = connection.read(tempBuffer)
        if (bytesRead == -1 || bytesRead == 0) return -1L
        
        sink.write(tempBuffer, 0, bytesRead)
        return bytesRead.toLong()
    }

    override fun timeout(): Timeout = Timeout.NONE
}

/**
 * Adapte l'interface [Connection] en un [Sink] Okio.
 */
class ConnectionSink(private val connection: Connection) : Sink {
    override fun close() {
    }

    override fun flush() {
    }

    override fun write(source: Buffer, byteCount: Long) {
         throw UnsupportedOperationException("Call suspendWrite instead")
    }

    suspend fun suspendWrite(source: Buffer, byteCount: Long) {
        var remaining = byteCount
        val tempBuffer = ByteArray(8192)
        while (remaining > 0) {
            val toRead = minOf(remaining, tempBuffer.size.toLong()).toInt()
            val bytesRead = source.read(tempBuffer, 0, toRead)
            if (bytesRead == -1) break
            connection.write(tempBuffer, 0, bytesRead)
            remaining -= bytesRead
        }
    }

    override fun timeout(): Timeout = Timeout.NONE
}
