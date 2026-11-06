package com.fastdrop.session

import com.fastdrop.createInMemoryConnectionPair
import com.fastdrop.createTestCryptographyProvider
import com.fastdrop.core.Peer
import com.fastdrop.core.TransportType
import com.fastdrop.security.*
import com.fastdrop.transfer.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okio.Buffer
import okio.Sink
import kotlin.test.Test
import kotlin.test.assertTrue

class SecureSessionTransferTest {
    @Test fun windowsUsesAndroidInitiatedChannelForFileTransfer() = runTest {
        val cryptoA = createTestCryptographyProvider()
        val cryptoB = createTestCryptographyProvider()
        val idA = TestIdentityStore(cryptoA)
        val idB = TestIdentityStore(cryptoB)
        val trustA = TestTrustedPeerStore()
        val trustB = TestTrustedPeerStore()
        val bytes = ByteArray(71) { it.toByte() }
        val destination = Buffer()
        val (rawA, rawB) = createInMemoryConnectionPair(
            Peer("Android", "Android", TransportType.LAN, "127.0.0.1"),
            Peer("Windows", "Windows", TransportType.LAN, "127.0.0.2"), maxReadSize = 3)
        val android = SecureChannel(rawA, idA, trustA, TestDeviceInfoProvider("Android"), cryptoA)
        val windows = SecureChannel(rawB, idB, trustB, TestDeviceInfoProvider("Windows"), cryptoB)
        val manager = TransferManager(chunkSize = 7)
        val receiver = async(Dispatchers.Default) {
            withTimeout(10_000) {
                (android.handshake() as PeerVerification.NewPeer).also { android.confirmPeer(it) }
                assertTrue(SessionProtocol(android).offerReceive { true })
                manager.receiveFile(android, { object : IncomingFileDestination {
                    override suspend fun openSink(): Sink = destination
                    override suspend fun commit() {}
                    override suspend fun abort() {}
                } })
            }
        }
        val sender = async(Dispatchers.Default) {
            withTimeout(10_000) {
                (windows.handshake() as PeerVerification.NewPeer).also { windows.confirmPeer(it) }
                val incoming = SessionProtocol(windows).acceptIncoming({ true }) {
                    assertTrue(SessionProtocol(windows).beginPreparedSend())
                    manager.sendFile(windows, object : TransferFileSource {
                        override val name = "android-bound.bin"
                        override val size = bytes.size.toLong()
                        override suspend fun openSource() = Buffer().write(bytes)
                    })
                }
                assertTrue(incoming == IncomingSessionResult.PREPARED_DONE)
            }
        }
        assertTrue(receiver.await())
        sender.await()
        assertTrue(bytes.contentEquals(destination.readByteArray()))
        android.close()
        windows.close()
    }

    @Test fun newSasThenTrustedReconnectAndTwoNegotiatedTransfers() = runTest {
        val cryptoA = createTestCryptographyProvider()
        val cryptoB = createTestCryptographyProvider()
        val identityA = TestIdentityStore(cryptoA)
        val identityB = TestIdentityStore(cryptoB)
        val trustA = TestTrustedPeerStore()
        val trustB = TestTrustedPeerStore()
        val manager = TransferManager(chunkSize = 5)
        repeat(2) { index ->
            val peers = Peer("A", "A", TransportType.LAN, "127.0.0.1") to
                Peer("B", "B", TransportType.LAN, "127.0.0.2")
            val (rawA, rawB) = createInMemoryConnectionPair(peers.first, peers.second, maxReadSize = 3)
            val secureA = SecureChannel(rawA, identityA, trustA, TestDeviceInfoProvider("A"), cryptoA)
            val secureB = SecureChannel(rawB, identityB, trustB, TestDeviceInfoProvider("B"), cryptoB)
            val bytes = ByteArray(64) { (it + index).toByte() }
            val destination = Buffer()
            val sender = async(Dispatchers.Default) {
                withTimeout(10_000) {
                    when (val verification = secureA.handshake()) {
                        is PeerVerification.NewPeer -> {
                            assertTrue(index == 0)
                            secureA.confirmPeer(verification)
                        }
                        is PeerVerification.TrustedPeer -> assertTrue(index == 1)
                    }
                    assertTrue(SessionProtocol(secureA).requestSend())
                    manager.sendFile(secureA, object : TransferFileSource {
                        override val name = "file-$index.bin"
                        override val size = bytes.size.toLong()
                        override suspend fun openSource() = Buffer().write(bytes)
                    })
                }
            }
            val receiver = async(Dispatchers.Default) {
                withTimeout(10_000) {
                    when (val verification = secureB.handshake()) {
                        is PeerVerification.NewPeer -> {
                            assertTrue(index == 0)
                            secureB.confirmPeer(verification)
                        }
                        is PeerVerification.TrustedPeer -> assertTrue(index == 1)
                    }
                    assertTrue(SessionProtocol(secureB).acceptSendIntent { true })
                    manager.receiveFile(secureB, { object : IncomingFileDestination {
                        override suspend fun openSink(): Sink = destination
                        override suspend fun commit() {}
                        override suspend fun abort() {}
                    } })
                }
            }
            assertTrue(sender.await())
            assertTrue(receiver.await())
            assertTrue(bytes.contentEquals(destination.readByteArray()))
            secureA.close()
            secureB.close()
        }
    }
}
