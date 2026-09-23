package com.fastdrop

import com.fastdrop.core.Peer
import com.fastdrop.core.TransportType
import com.fastdrop.security.*
import com.fastdrop.transfer.*
import com.fastdrop.transfer.TransferManager
import com.fastdrop.transport.ManualLanTransport
import dev.whyoleg.cryptography.CryptographyProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TcpIntegrationTest {
    @Test
    fun testRealTcpTransfer() = runTest(timeout = kotlin.time.Duration.parse("30s")) {
        val port = 47833
        val transportReceiver = ManualLanTransport(port)
        val transportSender = ManualLanTransport(port) // Sender uses random port technically, but just instantiate
        
        val p1 = com.fastdrop.createTestCryptographyProvider()
        val p2 = com.fastdrop.createTestCryptographyProvider()
        val idStore1 = TestIdentityStore(p1)
        val idStore2 = TestIdentityStore(p2)
        val trustStore1 = TestTrustedPeerStore()
        val trustStore2 = TestTrustedPeerStore()
        val transferManager = TransferManager()

        val f = File("test_in.bin")
        f.writeBytes(ByteArray(1024 * 1024) { (it % 256).toByte() })
        
        val receiveJob = async(Dispatchers.Default) {
            val flow = transportReceiver.startHosting()
            val conn = flow.first()
            val sec = SecureChannel(conn, idStore1, trustStore1, TestDeviceInfoProvider("Recv"), p1)
            val verif = sec.handshake() as PeerVerification.NewPeer
            sec.confirmPeer(verif)
            
            transferManager.receiveFile(
                connection = sec,
                onOfferReceived = { 
                    DesktopIncomingFileDestination(File("test_out.bin"))
                },
                onProgress = { _, _ -> }
            )
            transportReceiver.stop()
        }

        val sendJob = async(Dispatchers.Default) {
            kotlinx.coroutines.delay(2000)
            val conn = transportSender.connect(Peer("Target", "Target", TransportType.LAN, "127.0.0.1"))
            val sec = SecureChannel(conn, idStore2, trustStore2, TestDeviceInfoProvider("Sender"), p2)
            val verif = sec.handshake() as PeerVerification.NewPeer
            sec.confirmPeer(verif)
            
            transferManager.sendFile(
                connection = sec,
                fileSource = DesktopTransferFileSource(f),
                onProgress = { _, _ -> }
            )
            sec.close()
        }

        sendJob.await()
        receiveJob.await()
        
        val outF = File("test_out.bin")
        assertEquals(f.length(), outF.length())
        f.delete()
        outF.delete()
    }
}
