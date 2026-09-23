package com.fastdrop.transfer

import com.fastdrop.transport.Connection
import com.fastdrop.transport.readExactly
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import okio.Buffer
import okio.Source
import okio.Sink
import okio.HashingSink
import okio.HashingSource
import okio.blackholeSink
import okio.buffer

/**
 * Métadonnées d'un fichier à transférer.
 */
data class FileMetadata(
    val id: String,
    val name: String,
    val size: Long,
    val hash: String // Hash SHA-256 pour vérifier l'intégrité
)

/**
 * Gère le processus de transfert de fichiers applicatif par-dessus une connexion sécurisée.
 */
class TransferManager(
    private val chunkSize: Int = 1024 * 1024 // 1 MiB par défaut
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Lit une trame complète depuis la connexion.
     */
    private suspend fun readFrame(connection: Connection): Pair<Byte, ByteArray>? {
        val typeBytes = connection.readExactly(1) ?: return null
        val type = typeBytes[0]
        
        val lengthBytes = connection.readExactly(4) ?: return null
        val length = (lengthBytes[0].toInt() and 0xFF shl 24) or
                     (lengthBytes[1].toInt() and 0xFF shl 16) or
                     (lengthBytes[2].toInt() and 0xFF shl 8) or
                     (lengthBytes[3].toInt() and 0xFF)
                     
        if (length < 0) return null
        
        val payload = if (length > 0) {
            connection.readExactly(length) ?: return null
        } else {
            ByteArray(0)
        }
        
        return Pair(type, payload)
    }

    /**
     * Écrit une trame dans la connexion.
     */
    private suspend fun writeFrame(connection: Connection, type: Byte, payload: ByteArray) {
        val length = payload.size
        val header = ByteArray(5)
        header[0] = type
        header[1] = (length shr 24).toByte()
        header[2] = (length shr 16).toByte()
        header[3] = (length shr 8).toByte()
        header[4] = length.toByte()
        
        connection.write(header)
        if (payload.isNotEmpty()) {
            connection.write(payload)
        }
    }
    
    private suspend fun sendControlMessage(connection: Connection, message: ControlMessage) {
        val jsonString = json.encodeToString(message)
        writeFrame(connection, FrameType.CONTROL_MESSAGE, jsonString.encodeToByteArray())
    }

    /**
     * Propose et envoie un fichier.
     * @param onProgress Callback pour la progression (bytes sent, total bytes).
     */
    suspend fun sendFile(
        connection: Connection, 
        metadata: FileMetadata, 
        fileSource: Source,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // 1. Envoyer OFFER
            sendControlMessage(connection, ControlMessage.FileOffer(metadata.id, metadata.name, metadata.size, metadata.hash))
            
            // 2. Attendre ACCEPT ou REJECT
            val responseFrame = readFrame(connection) ?: return@withContext false
            if (responseFrame.first != FrameType.CONTROL_MESSAGE) return@withContext false
            
            val responseMsg = json.decodeFromString<ControlMessage>(responseFrame.second.decodeToString())
            if (responseMsg !is ControlMessage.FileAccept) {
                return@withContext false // Refusé ou annulé
            }
            
            // 3. Envoyer les Chunks
            val buffer = Buffer()
            var offset = 0L
            val hashingSource = HashingSource.sha256(fileSource)
            
            while (offset < metadata.size) {
                val read = hashingSource.read(buffer, chunkSize.toLong())
                if (read == -1L) break
                
                val chunkData = buffer.readByteArray()
                
                // Préparer le payload du CHUNK: [8 bytes offset] + [data]
                val chunkPayload = ByteArray(8 + chunkData.size)
                for (i in 0..7) {
                    chunkPayload[i] = (offset shr ((7 - i) * 8)).toByte()
                }
                chunkData.copyInto(chunkPayload, 8)
                
                writeFrame(connection, FrameType.CHUNK, chunkPayload)
                offset += read
                
                onProgress(offset, metadata.size)
            }
            
            // 4. Envoyer COMPLETE avec le hash calculé
            val computedHash = hashingSource.hash.hex()
            sendControlMessage(connection, ControlMessage.Complete(metadata.id, computedHash))
            
            // 5. Attendre SUCCESS
            val finalFrame = readFrame(connection) ?: return@withContext false
            if (finalFrame.first == FrameType.CONTROL_MESSAGE) {
                val finalMsg = json.decodeFromString<ControlMessage>(finalFrame.second.decodeToString())
                return@withContext finalMsg is ControlMessage.Success
            }
            return@withContext false
        } catch (e: Exception) {
            try {
                sendControlMessage(connection, ControlMessage.Error(metadata.id, e.message ?: "Unknown error"))
            } catch (ignored: Exception) {}
            return@withContext false
        } finally {
            fileSource.close()
        }
    }

    /**
     * Reçoit un fichier depuis la connexion.
     */
    suspend fun receiveFile(
        connection: Connection, 
        onOfferReceived: suspend (ControlMessage.FileOffer) -> Boolean,
        fileSink: Sink,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        var hashingSink: HashingSink? = null
        var metadata: ControlMessage.FileOffer? = null
        
        try {
            // 1. Lire OFFER
            val offerFrame = readFrame(connection) ?: return@withContext false
            if (offerFrame.first != FrameType.CONTROL_MESSAGE) return@withContext false
            
            val offerMsg = json.decodeFromString<ControlMessage>(offerFrame.second.decodeToString())
            if (offerMsg !is ControlMessage.FileOffer) return@withContext false
            
            metadata = offerMsg
            
            // 2. Demander à l'utilisateur/app si on accepte
            val accepted = onOfferReceived(offerMsg)
            if (!accepted) {
                sendControlMessage(connection, ControlMessage.FileReject(offerMsg.id))
                return@withContext false
            }
            
            sendControlMessage(connection, ControlMessage.FileAccept(offerMsg.id))
            
            hashingSink = HashingSink.sha256(fileSink)
            val bufferedSink = hashingSink.buffer()
            
            var receivedBytes = 0L
            
            // 3. Recevoir les Chunks
            while (receivedBytes < offerMsg.size) {
                val frame = readFrame(connection) ?: return@withContext false
                
                if (frame.first == FrameType.CONTROL_MESSAGE) {
                    val msg = json.decodeFromString<ControlMessage>(frame.second.decodeToString())
                    if (msg is ControlMessage.Cancel || msg is ControlMessage.Error) {
                        return@withContext false
                    }
                    if (msg is ControlMessage.Complete) {
                        // Le transfert a fini plus tôt que prévu ou on a reçu COMPLETE
                        break
                    }
                } else if (frame.first == FrameType.CHUNK) {
                    val payload = frame.second
                    if (payload.size < 8) continue // Erreur de trame
                    
                    var offset = 0L
                    for (i in 0..7) {
                        offset = (offset shl 8) or (payload[i].toLong() and 0xFF)
                    }
                    
                    val chunkData = payload.copyOfRange(8, payload.size)
                    
                    // Note: Dans une implémentation robuste, il faudrait gérer l'offset (reprise), 
                    // ici on suppose une écriture séquentielle.
                    bufferedSink.write(chunkData)
                    bufferedSink.emit() // flush au sink sous-jacent
                    
                    receivedBytes += chunkData.size
                    onProgress(receivedBytes, offerMsg.size)
                }
            }
            
            bufferedSink.flush()
            
            // 4. Attendre COMPLETE si pas déjà reçu
            val completeFrame = readFrame(connection) ?: return@withContext false
            if (completeFrame.first != FrameType.CONTROL_MESSAGE) return@withContext false
            
            val completeMsg = json.decodeFromString<ControlMessage>(completeFrame.second.decodeToString())
            if (completeMsg !is ControlMessage.Complete) return@withContext false
            
            // 5. Valider le hash
            val computedHash = hashingSink.hash.hex()
            if (computedHash.equals(completeMsg.hash, ignoreCase = true) && 
                computedHash.equals(metadata.hash, ignoreCase = true)) {
                sendControlMessage(connection, ControlMessage.Success(metadata.id))
                return@withContext true
            } else {
                sendControlMessage(connection, ControlMessage.Error(metadata.id, "Hash mismatch"))
                return@withContext false
            }
            
        } catch (e: Exception) {
            if (metadata != null) {
                try {
                    sendControlMessage(connection, ControlMessage.Error(metadata.id, e.message ?: "Unknown error"))
                } catch (ignored: Exception) {}
            }
            return@withContext false
        } finally {
            fileSink.close()
        }
    }
}
