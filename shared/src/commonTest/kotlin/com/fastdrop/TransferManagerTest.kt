package com.fastdrop

import com.fastdrop.core.Peer
import com.fastdrop.core.TransportType
import com.fastdrop.transfer.FileMetadata
import com.fastdrop.transfer.TransferManager
import com.fastdrop.transport.Connection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransferManagerTest {

    @Test
    fun testFileTransfer() = runTest {
        val transferManager = TransferManager(chunkSize = 1024) // 1 KB chunks
        
        // Simuler un fichier de 5 KB
        val originalData = ByteArray(5 * 1024) { (it % 256).toByte() }
        val sourceBuffer = Buffer().write(originalData)
        val sinkBuffer = Buffer()
        
        val peer1 = Peer("1", "P1", TransportType.LAN, "127.0.0.1")
        val peer2 = Peer("2", "P2", TransportType.LAN, "127.0.0.2")
        
        // Canal de communication en mémoire
        val (conn1, conn2) = createInMemoryConnectionPair(peer1, peer2)
        
        // Hash manuel pour le test
        val hash = okio.HashingSource.sha256(Buffer().write(originalData)).use { it.hash.hex() }
        val metadata = FileMetadata("file_1", "test.bin", originalData.size.toLong(), hash)
        
        var bytesSent = 0L
        var bytesReceived = 0L
        
        val senderJob = async(Dispatchers.Default) {
            transferManager.sendFile(
                connection = conn1,
                metadata = metadata,
                fileSource = sourceBuffer,
                onProgress = { current, total -> bytesSent = current }
            )
        }
        
        val receiverJob = async(Dispatchers.Default) {
            transferManager.receiveFile(
                connection = conn2,
                onOfferReceived = { true }, // Accepte toujours
                fileSink = sinkBuffer,
                onProgress = { current, total -> bytesReceived = current }
            )
        }
        
        val sendResult = senderJob.await()
        val receiveResult = receiverJob.await()
        
        assertTrue(sendResult, "Sender failed")
        assertTrue(receiveResult, "Receiver failed")
        
        assertEquals(originalData.size.toLong(), bytesSent)
        assertEquals(originalData.size.toLong(), bytesReceived)
        
        val receivedData = sinkBuffer.readByteArray()
        assertTrue(originalData.contentEquals(receivedData), "Données reçues corrompues")
    }
}
