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
import kotlin.test.assertFalse

class TransferManagerTest {

    private fun createPeers(): Pair<Peer, Peer> {
        return Peer("1", "P1", TransportType.LAN, "127.0.0.1") to Peer("2", "P2", TransportType.LAN, "127.0.0.2")
    }

    @Test
    fun testFileTransferNormal() = runTest {
        runTransferTest(
            dataSize = 5 * 1024, 
            chunkSize = 1024, 
            maxReadSize = Int.MAX_VALUE
        )
    }
    
    @Test
    fun testFileTransferEmpty() = runTest {
        runTransferTest(
            dataSize = 0, 
            chunkSize = 1024, 
            maxReadSize = Int.MAX_VALUE
        )
    }
    
    @Test
    fun testFileTransferSmall() = runTest {
        runTransferTest(
            dataSize = 10, 
            chunkSize = 1024, 
            maxReadSize = Int.MAX_VALUE
        )
    }
    
    @Test
    fun testFileTransferOddSize() = runTest {
        runTransferTest(
            dataSize = 3333, 
            chunkSize = 1000, 
            maxReadSize = Int.MAX_VALUE
        )
    }

    @Test
    fun testFileTransferFragmented() = runTest {
        // TCP fragmentation: read at most 3 bytes at a time
        // This will fragment the 5-byte header heavily, forcing readExactly to loop
        runTransferTest(
            dataSize = 5 * 1024, 
            chunkSize = 1024, 
            maxReadSize = 3 
        )
    }
    
    @Test
    fun testFileTransferHashMismatch() = runTest {
        val transferManager = TransferManager(chunkSize = 1024)
        val data = ByteArray(1024) { 1 }
        val sourceBuffer = Buffer().write(data)
        val sinkBuffer = Buffer()
        
        val (peer1, peer2) = createPeers()
        val (conn1, conn2) = createInMemoryConnectionPair(peer1, peer2)
        
        // Simuler un envoi manuel avec un mauvais hash
        val senderJob = async(Dispatchers.Default) {
            val type = 0x01.toByte() // CONTROL
            val msg = """{"type":"com.fastdrop.transfer.ControlMessage.Complete","id":"1","hash":"badhash"}"""
            val payload = msg.encodeToByteArray()
            val header = ByteArray(5)
            header[0] = type
            header[1] = (payload.size shr 24).toByte()
            header[2] = (payload.size shr 16).toByte()
            header[3] = (payload.size shr 8).toByte()
            header[4] = payload.size.toByte()
            
            // Envoyer l'offre
            val offer = """{"type":"com.fastdrop.transfer.ControlMessage.FileOffer","id":"1","name":"a","size":0}"""
            val offerPayload = offer.encodeToByteArray()
            val offerHeader = ByteArray(5)
            offerHeader[0] = type
            offerHeader[1] = 0; offerHeader[2] = 0; offerHeader[3] = 0; offerHeader[4] = offerPayload.size.toByte()
            conn1.write(offerHeader)
            conn1.write(offerPayload)
            
            // Lire Accept
            val accHeader = ByteArray(5); conn1.read(accHeader)
            val accPayload = ByteArray(accHeader[4].toInt()); conn1.read(accPayload)
            
            // Envoyer le mauvais complete
            conn1.write(header)
            conn1.write(payload)
            
            // Attendre la réponse (devrait être HashMismatch ou Error)
            val respHeader = ByteArray(5)
            conn1.read(respHeader)
        }
        
        val receiverJob = async(Dispatchers.Default) {
            transferManager.receiveFile(
                connection = conn2,
                onOfferReceived = { true },
                fileSink = sinkBuffer
            )
        }
        
        assertFalse(receiverJob.await(), "Le transfert aurait dû échouer à cause du mauvais hash")
    }

    private suspend fun runTransferTest(dataSize: Int, chunkSize: Int, maxReadSize: Int) {
        val transferManager = TransferManager(chunkSize = chunkSize)
        
        val originalData = ByteArray(dataSize) { (it % 256).toByte() }
        val sourceBuffer = Buffer().write(originalData)
        val sinkBuffer = Buffer()
        
        val (peer1, peer2) = createPeers()
        val (conn1, conn2) = createInMemoryConnectionPair(peer1, peer2, maxReadSize = maxReadSize)
        
        val metadata = FileMetadata("file_$dataSize", "test.bin", originalData.size.toLong())
        
        val senderJob = async(Dispatchers.Default) {
            transferManager.sendFile(
                connection = conn1,
                metadata = metadata,
                fileSource = sourceBuffer
            )
        }
        
        val receiverJob = async(Dispatchers.Default) {
            transferManager.receiveFile(
                connection = conn2,
                onOfferReceived = { true },
                fileSink = sinkBuffer
            )
        }
        
        val sendResult = senderJob.await()
        val receiveResult = receiverJob.await()
        
        assertTrue(sendResult, "Sender failed")
        assertTrue(receiveResult, "Receiver failed")
        
        val receivedData = sinkBuffer.readByteArray()
        assertTrue(originalData.contentEquals(receivedData), "Données reçues corrompues")
    }
}
