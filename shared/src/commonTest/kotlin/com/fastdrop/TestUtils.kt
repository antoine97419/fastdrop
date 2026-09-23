package com.fastdrop

import dev.whyoleg.cryptography.CryptographyProvider
import com.fastdrop.core.Peer
import com.fastdrop.transport.Connection
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import okio.Buffer

class InMemoryStream(private val maxReadSize: Int = Int.MAX_VALUE) {
    private val buffer = Buffer()
    private val mutex = Mutex()
    private var isClosed = false

    suspend fun write(data: ByteArray, offset: Int, length: Int) {
        mutex.withLock {
            if (isClosed) throw IllegalStateException("Stream closed")
            buffer.write(data, offset, length)
        }
    }

    suspend fun read(outBuffer: ByteArray): Int {
        while (true) {
            mutex.withLock {
                if (buffer.size > 0) {
                    // Simule la fragmentation TCP en limitant la lecture
                    val limit = minOf(outBuffer.size.toLong(), maxReadSize.toLong(), buffer.size)
                    val read = buffer.read(outBuffer, 0, limit.toInt())
                    return read
                }
                if (isClosed) return -1
            }
            delay(5)
        }
    }
    
    fun close() {
        isClosed = true
    }
}

fun createInMemoryConnectionPair(peer1: Peer, peer2: Peer, maxReadSize: Int = Int.MAX_VALUE): Pair<Connection, Connection> {
    val stream1to2 = InMemoryStream(maxReadSize)
    val stream2to1 = InMemoryStream(maxReadSize)
    
    val conn1 = object : Connection {
        override val peer = peer2
        override suspend fun read(buffer: ByteArray) = stream2to1.read(buffer)
        override suspend fun write(data: ByteArray, offset: Int, length: Int) = stream1to2.write(data, offset, length)
        override suspend fun close() {
            stream1to2.close()
            stream2to1.close()
        }
    }
    
    val conn2 = object : Connection {
        override val peer = peer1
        override suspend fun read(buffer: ByteArray) = stream1to2.read(buffer)
        override suspend fun write(data: ByteArray, offset: Int, length: Int) = stream2to1.write(data, offset, length)
        override suspend fun close() {
            stream2to1.close()
            stream1to2.close()
        }
    }
    
    return Pair(conn1, conn2)
}


expect fun createTestCryptographyProvider(): CryptographyProvider
