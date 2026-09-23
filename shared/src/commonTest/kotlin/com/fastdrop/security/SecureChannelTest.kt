package com.fastdrop.security

import com.fastdrop.core.Peer
import com.fastdrop.core.TransportType
import com.fastdrop.createInMemoryConnectionPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SecureChannelTest {

    private fun createPeers(): Pair<Peer, Peer> {
        return Peer("1", "P1", TransportType.LAN, "127.0.0.1") to Peer("2", "P2", TransportType.LAN, "127.0.0.2")
    }

    @Test
    fun testHandshakeAndSas() = runTest {
        val (peer1, peer2) = createPeers()
        val (conn1, conn2) = createInMemoryConnectionPair(peer1, peer2)

        val sec1 = SecureChannel(conn1)
        val sec2 = SecureChannel(conn2)

        val job1 = async(Dispatchers.Default) { sec1.handshake() }
        val job2 = async(Dispatchers.Default) { sec2.handshake() }

        val res1 = job1.await()
        val res2 = job2.await()

        assertEquals(res1.sas, res2.sas)

        sec1.confirmPeer()
        sec2.confirmPeer()

        assertEquals(SecureChannelState.ESTABLISHED, sec1.state)
        assertEquals(SecureChannelState.ESTABLISHED, sec2.state)
    }

    @Test
    fun testEncryptionDecryption() = runTest {
        val (peer1, peer2) = createPeers()
        val (conn1, conn2) = createInMemoryConnectionPair(peer1, peer2)

        val sec1 = SecureChannel(conn1)
        val sec2 = SecureChannel(conn2)

        async(Dispatchers.Default) {
            sec1.handshake()
            sec1.confirmPeer()
        }
        sec2.handshake()
        sec2.confirmPeer()

        val dispatcher1 = kotlinx.coroutines.newSingleThreadContext("Peer1")
        val dispatcher2 = kotlinx.coroutines.newSingleThreadContext("Peer2")

        val data = "hello FastDrop".encodeToByteArray()
        
        async(dispatcher1) {
            sec1.write(data, 0, data.size)
        }
        
        val readBuffer = ByteArray(1024)
        val readLen = kotlinx.coroutines.withContext(dispatcher2) {
            sec2.read(readBuffer)
        }
        
        val readData = readBuffer.copyOfRange(0, readLen)
        assertTrue(data.contentEquals(readData))
    }

    @Test
    fun testCorruption() = runTest {
        val (peer1, peer2) = createPeers()
        val stream1 = com.fastdrop.InMemoryStream()
        val stream2 = com.fastdrop.InMemoryStream()
        
        val conn1 = object : com.fastdrop.transport.Connection {
            override val peer = peer2
            override suspend fun read(buffer: ByteArray) = stream2.read(buffer)
            override suspend fun write(data: ByteArray, offset: Int, length: Int) {
                if (length > 40) {
                    val corrupted = data.copyOfRange(offset, offset + length)
                    corrupted[length - 1] = (corrupted[length - 1] + 1).toByte()
                    stream1.write(corrupted, 0, length)
                } else {
                    stream1.write(data, offset, length)
                }
            }
            override suspend fun close() {}
        }
        val conn2 = object : com.fastdrop.transport.Connection {
            override val peer = peer1
            override suspend fun read(buffer: ByteArray) = stream1.read(buffer)
            override suspend fun write(data: ByteArray, offset: Int, length: Int) = stream2.write(data, offset, length)
            override suspend fun close() {}
        }

        val sec1 = SecureChannel(conn1)
        val sec2 = SecureChannel(conn2)

        val j = async(Dispatchers.Default) {
            sec1.handshake()
            sec1.confirmPeer()
            sec1.write("Hello".encodeToByteArray(), 0, 5)
        }
        sec2.handshake()
        sec2.confirmPeer()
        
        val buf = ByteArray(100)
        try {
            sec2.read(buf)
            assertTrue(false, "Devrait échouer")
        } catch (e: Exception) {
            // Expected
        }
        j.cancel()
    }
}
