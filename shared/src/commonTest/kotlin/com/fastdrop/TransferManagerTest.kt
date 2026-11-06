package com.fastdrop

import com.fastdrop.core.Peer
import com.fastdrop.core.TransportType
import com.fastdrop.security.PeerVerification
import com.fastdrop.security.SecureChannel
import com.fastdrop.security.TestDeviceInfoProvider
import com.fastdrop.security.TestIdentityStore
import com.fastdrop.security.TestTrustedPeerStore
import com.fastdrop.transfer.ControlMessage
import com.fastdrop.transfer.FrameType
import com.fastdrop.transfer.IncomingFileDestination
import com.fastdrop.transfer.TransferFileSource
import com.fastdrop.transfer.TransferManager
import com.fastdrop.transport.Connection
import com.fastdrop.transport.readExactly
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.Sink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TransferManagerTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun createPeers() =
        Peer("1", "P1", TransportType.LAN, "127.0.0.1") to
            Peer("2", "P2", TransportType.LAN, "127.0.0.2")

    @Test
    fun testSmallTextFile() = runTest {
        val text = "Hello FastDrop!\n".encodeToByteArray()
        runSecureTransfer(text, chunkSize = 1024, fileName = "hello.txt")
    }

    @Test
    fun testFileLargerThanOneChunk() = runTest {
        runSecureTransfer(ByteArray(5 * 1024) { (it % 256).toByte() }, chunkSize = 1024)
    }

    @Test
    fun testEmptyFile() = runTest {
        runSecureTransfer(ByteArray(0), chunkSize = 1024)
    }

    @Test
    fun testOddSizedFile() = runTest {
        runSecureTransfer(ByteArray(3333) { (it % 256).toByte() }, chunkSize = 1000)
    }

    @Test
    fun testFragmentedSecureConnection() = runTest {
        runSecureTransfer(
            ByteArray(5 * 1024) { (it % 256).toByte() },
            chunkSize = 1024,
            maxReadSize = 3
        )
    }

    @Test
    fun testApplicationFrameUsesSingleConnectionWrite() = runTest {
        val manager = TransferManager(chunkSize = 4)
        val data = "abcdef".encodeToByteArray()
        val (peer1, peer2) = createPeers()
        val (rawSender, rawReceiver) = createInMemoryConnectionPair(peer1, peer2)
        val sender = RecordingConnection(rawSender)
        val receiver = RecordingConnection(rawReceiver)
        val sink = Buffer()

        val sendJob = async(Dispatchers.Default) { manager.sendFile(sender, bufferSource("atomic.txt", data)) }
        val receiveJob = async(Dispatchers.Default) {
            manager.receiveFile(receiver, { bufferDestination(sink) })
        }

        assertTrue(sendJob.await())
        assertTrue(receiveJob.await())
        assertEquals(4, sender.writeSizes.size, "offer + 2 chunks + complete")
        assertEquals(2, receiver.writeSizes.size, "accept + success")
        assertTrue(sender.writeSizes.all { it > 5 }, "each write must contain header and payload")
        assertTrue(receiver.writeSizes.all { it > 5 }, "each write must contain header and payload")
        assertTrue(data.contentEquals(sink.readByteArray()))
    }

    @Test
    fun testHashMismatchIsReportedToSender() = runTest {
        val manager = TransferManager(chunkSize = 4)
        val data = "hash me".encodeToByteArray()
        val (peer1, peer2) = createPeers()
        val (rawSender, receiver) = createInMemoryConnectionPair(peer1, peer2)
        val sender = CorruptingChunkConnection(rawSender)

        val sendJob = async(Dispatchers.Default) { manager.sendFile(sender, bufferSource("hash.txt", data)) }
        val receiveJob = async(Dispatchers.Default) {
            manager.receiveFile(receiver, { bufferDestination(Buffer()) })
        }

        assertFalse(sendJob.await(), "Sender should receive HASH_MISMATCH")
        assertFalse(receiveJob.await(), "Receiver should reject the corrupted hash")
        assertTrue(sender.corrupted)
    }

    @Test
    fun testEarlyCompleteIsNotReadTwice() = runTest {
        val manager = TransferManager(chunkSize = 1024)
        val (peer1, peer2) = createPeers()
        val (sender, receiver) = createInMemoryConnectionPair(peer1, peer2)
        val receiveJob = async(Dispatchers.Default) {
            manager.receiveFile(receiver, { bufferDestination(Buffer()) })
        }

        sender.write(controlFrame(ControlMessage.FileOffer("early", "short.txt", 10)))
        assertIs<ControlMessage.FileAccept>(readControlMessage(sender))
        sender.write(
            controlFrame(
                ControlMessage.Complete(
                    "early",
                    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
                )
            )
        )

        assertIs<ControlMessage.HashMismatch>(withTimeout(5_000) { readControlMessage(sender) })
        assertFalse(withTimeout(5_000) { receiveJob.await() })
    }

    @Test
    fun testTrustedReconnectThenSecureTransfer() = runTest {
        val (peer1, peer2) = createPeers()
        val provider1 = createTestCryptographyProvider()
        val provider2 = createTestCryptographyProvider()
        val identity1 = TestIdentityStore(provider1)
        val identity2 = TestIdentityStore(provider2)
        val trust1 = TestTrustedPeerStore()
        val trust2 = TestTrustedPeerStore()

        val (firstRaw1, firstRaw2) = createInMemoryConnectionPair(peer1, peer2)
        val first1 = SecureChannel(firstRaw1, identity1, trust1, TestDeviceInfoProvider("A"), provider1)
        val first2 = SecureChannel(firstRaw2, identity2, trust2, TestDeviceInfoProvider("B"), provider2)
        val firstJob = async(Dispatchers.Default) { first1.handshake() }
        val firstResult2 = first2.handshake()
        val firstResult1 = firstJob.await()
        assertIs<PeerVerification.NewPeer>(firstResult1)
        assertIs<PeerVerification.NewPeer>(firstResult2)
        first1.confirmPeer(firstResult1)
        first2.confirmPeer(firstResult2)
        first1.close()

        val (secondRaw1, secondRaw2) = createInMemoryConnectionPair(peer1, peer2, maxReadSize = 3)
        val second1 = SecureChannel(secondRaw1, identity1, trust1, TestDeviceInfoProvider("A"), provider1)
        val second2 = SecureChannel(secondRaw2, identity2, trust2, TestDeviceInfoProvider("B"), provider2)
        val manager = TransferManager(chunkSize = 5)
        val data = "trusted reconnect transfer".encodeToByteArray()
        val sink = Buffer()

        val sendJob = async(Dispatchers.Default) {
            withTimeout(10_000) {
                assertIs<PeerVerification.TrustedPeer>(second1.handshake())
                manager.sendFile(second1, bufferSource("trusted.txt", data))
            }
        }
        val receiveJob = async(Dispatchers.Default) {
            withTimeout(10_000) {
                assertIs<PeerVerification.TrustedPeer>(second2.handshake())
                manager.receiveFile(second2, { bufferDestination(sink) })
            }
        }

        assertTrue(sendJob.await())
        assertTrue(receiveJob.await())
        assertTrue(data.contentEquals(sink.readByteArray()))
    }

    private suspend fun runSecureTransfer(
        data: ByteArray,
        chunkSize: Int,
        fileName: String = "test.bin",
        maxReadSize: Int = Int.MAX_VALUE
    ) = coroutineScope {
        val manager = TransferManager(chunkSize)
        val sink = Buffer()
        val (peer1, peer2) = createPeers()
        val (connection1, connection2) = createInMemoryConnectionPair(peer1, peer2, maxReadSize)
        val provider1 = createTestCryptographyProvider()
        val provider2 = createTestCryptographyProvider()
        val secure1 = SecureChannel(
            connection1, TestIdentityStore(provider1), TestTrustedPeerStore(),
            TestDeviceInfoProvider("A"), provider1
        )
        val secure2 = SecureChannel(
            connection2, TestIdentityStore(provider2), TestTrustedPeerStore(),
            TestDeviceInfoProvider("B"), provider2
        )

        val sendJob = async(Dispatchers.Default) {
            secure1.handshake()
            secure1.confirmPeer()
            manager.sendFile(secure1, bufferSource(fileName, data))
        }
        val receiveJob = async(Dispatchers.Default) {
            secure2.handshake()
            secure2.confirmPeer()
            manager.receiveFile(secure2, { bufferDestination(sink) })
        }

        assertTrue(sendJob.await(), "Sender failed")
        assertTrue(receiveJob.await(), "Receiver failed")
        assertTrue(data.contentEquals(sink.readByteArray()), "Received data differs")
    }

    private fun bufferSource(fileName: String, data: ByteArray) = object : TransferFileSource {
        override val name = fileName
        override val size = data.size.toLong()
        override suspend fun openSource() = Buffer().write(data)
    }

    private fun bufferDestination(buffer: Buffer) = object : IncomingFileDestination {
        override suspend fun openSink(): Sink = buffer
        override suspend fun commit() {}
        override suspend fun abort() {}
    }

    private fun controlFrame(message: ControlMessage): ByteArray {
        val payload = json.encodeToString<ControlMessage>(message).encodeToByteArray()
        return byteArrayOf(
            FrameType.CONTROL_MESSAGE,
            (payload.size shr 24).toByte(),
            (payload.size shr 16).toByte(),
            (payload.size shr 8).toByte(),
            payload.size.toByte()
        ) + payload
    }

    private suspend fun readControlMessage(connection: Connection): ControlMessage {
        val header = connection.readExactly(5) ?: error("EOF while reading test frame header")
        assertEquals(FrameType.CONTROL_MESSAGE, header[0])
        val length = ((header[1].toInt() and 0xFF) shl 24) or
            ((header[2].toInt() and 0xFF) shl 16) or
            ((header[3].toInt() and 0xFF) shl 8) or
            (header[4].toInt() and 0xFF)
        val payload = connection.readExactly(length) ?: error("EOF while reading test frame payload")
        return json.decodeFromString(payload.decodeToString())
    }

    private class RecordingConnection(private val delegate: Connection) : Connection {
        override val peer = delegate.peer
        val writeSizes = mutableListOf<Int>()
        override suspend fun read(buffer: ByteArray) = delegate.read(buffer)
        override suspend fun write(data: ByteArray, offset: Int, length: Int) {
            writeSizes += length
            delegate.write(data, offset, length)
        }
        override suspend fun close() = delegate.close()
    }

    private class CorruptingChunkConnection(private val delegate: Connection) : Connection {
        override val peer = delegate.peer
        var corrupted = false
            private set

        override suspend fun read(buffer: ByteArray) = delegate.read(buffer)
        override suspend fun write(data: ByteArray, offset: Int, length: Int) {
            val copy = data.copyOfRange(offset, offset + length)
            if (!corrupted && copy.size > 13 && copy[0] == FrameType.CHUNK) {
                copy[13] = (copy[13].toInt() xor 0x01).toByte()
                corrupted = true
            }
            delegate.write(copy)
        }
        override suspend fun close() = delegate.close()
    }
}
