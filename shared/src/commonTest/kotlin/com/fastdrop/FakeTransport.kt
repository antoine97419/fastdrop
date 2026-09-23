package com.fastdrop

import com.fastdrop.core.Peer
import com.fastdrop.core.TransportType
import com.fastdrop.transport.Connection
import com.fastdrop.transport.Transport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.emptyFlow

class FakeTransport(private val testPeers: List<Peer>) : Transport {
    override val type: TransportType = TransportType.LAN

    override suspend fun discover(): Flow<List<Peer>> {
        return flowOf(testPeers)
    }

    override suspend fun connect(peer: Peer): Connection {
        return FakeConnection(peer)
    }

    override suspend fun startHosting(): Flow<Connection> {
        return emptyFlow() // Implémenter selon les besoins du test
    }

    override suspend fun stop() {
        // No-op
    }
}

class FakeConnection(override val peer: Peer) : Connection {
    private val buffer = mutableListOf<Byte>()

    override suspend fun read(outBuffer: ByteArray): Int {
        // Simulation basique
        if (buffer.isEmpty()) return 0
        val sizeToRead = minOf(outBuffer.size, buffer.size)
        for (i in 0 until sizeToRead) {
            outBuffer[i] = buffer.removeAt(0)
        }
        return sizeToRead
    }

    override suspend fun write(data: ByteArray, offset: Int, length: Int) {
        for (i in offset until offset + length) {
            buffer.add(data[i])
        }
    }

    override suspend fun close() {
        buffer.clear()
    }
}
