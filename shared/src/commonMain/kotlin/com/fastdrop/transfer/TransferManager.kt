package com.fastdrop.transfer

import com.fastdrop.transport.Connection
import com.fastdrop.transport.readExactly
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.HashingSink
import okio.HashingSource
import okio.Source
import okio.buffer

data class FileMetadata(val id: String, val name: String, val size: Long)

class TransferManager(private val chunkSize: Int = 1024 * 1024) {
    private val json = Json { ignoreUnknownKeys = true }
    private val maxControlSize = 64 * 1024
    private val maxChunkSize = chunkSize + 8

    init {
        require(chunkSize > 0) { "chunkSize must be positive" }
    }

    private fun log(side: String, message: String) {
        println("[FastDrop][Transfer][$side] $message")
    }

    private fun logException(side: String, exception: Throwable) {
        log(side, "EXCEPTION ${exception::class.simpleName}: ${exception.message}")
        println(exception.stackTraceToString())
    }

    private fun frameTypeName(type: Byte): String = when (type) {
        FrameType.CONTROL_MESSAGE -> "CONTROL_MESSAGE"
        FrameType.CHUNK -> "CHUNK"
        else -> "UNKNOWN($type)"
    }

    private fun controlMessageName(message: ControlMessage): String =
        message::class.simpleName ?: "ControlMessage"

    private suspend fun readFrame(connection: Connection, side: String): Pair<Byte, ByteArray>? {
        val typeBytes = connection.readExactly(1)
        if (typeBytes == null) {
            log(side, "readFrame: EOF while waiting for frame type")
            return null
        }
        val type = typeBytes[0]
        require(type == FrameType.CONTROL_MESSAGE || type == FrameType.CHUNK) {
            "Unknown frame type: $type"
        }

        val lengthBytes = connection.readExactly(4)
        if (lengthBytes == null) {
            log(side, "readFrame: EOF while reading ${frameTypeName(type)} length")
            return null
        }
        val length = ((lengthBytes[0].toInt() and 0xFF) shl 24) or
            ((lengthBytes[1].toInt() and 0xFF) shl 16) or
            ((lengthBytes[2].toInt() and 0xFF) shl 8) or
            (lengthBytes[3].toInt() and 0xFF)

        require(length >= 0) { "Negative frame length" }
        require(type != FrameType.CONTROL_MESSAGE || length <= maxControlSize) {
            "Control frame too large: $length"
        }
        require(type != FrameType.CHUNK || length <= maxChunkSize) {
            "Chunk frame too large: $length"
        }

        val payload = if (length == 0) {
            ByteArray(0)
        } else {
            val bytes = connection.readExactly(length)
            if (bytes == null) {
                log(side, "readFrame: EOF while reading ${frameTypeName(type)} payload ($length bytes)")
                return null
            }
            bytes
        }

        log(side, "readFrame: type=${frameTypeName(type)}, payloadBytes=${payload.size}")
        return type to payload
    }

    private suspend fun writeFrame(connection: Connection, type: Byte, payload: ByteArray) {
        val length = payload.size
        val frame = ByteArray(5 + length)
        frame[0] = type
        frame[1] = (length shr 24).toByte()
        frame[2] = (length shr 16).toByte()
        frame[3] = (length shr 8).toByte()
        frame[4] = length.toByte()
        payload.copyInto(frame, destinationOffset = 5)

        // SecureChannel encrypts each write independently. Keep one application
        // frame in one encrypted record instead of splitting header and payload.
        connection.write(frame)
    }

    private suspend fun sendControlMessage(connection: Connection, message: ControlMessage) {
        writeFrame(
            connection,
            FrameType.CONTROL_MESSAGE,
            json.encodeToString(message).encodeToByteArray()
        )
    }

    fun sanitizeFilename(filename: String): String {
        return filename.replace("\\", "/").split("/").lastOrNull { it.isNotBlank() } ?: "unknown_file"
    }

    suspend fun sendFile(
        connection: Connection,
        fileSource: TransferFileSource,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        val metadata = FileMetadata(
            "transfer_${com.fastdrop.utils.getCurrentTimeMillis()}",
            fileSource.name,
            fileSource.size
        )
        var source: Source? = null

        try {
            require(metadata.name.isNotBlank()) { "File name is blank" }
            require(metadata.size >= 0) { "File size is negative: ${metadata.size}" }
            log("SEND", "opening source")
            log("SEND", "metadata id=${metadata.id}, name=${metadata.name}, size=${metadata.size}")
            source = fileSource.openSource()

            log("SEND", "sending FILE_OFFER")
            sendControlMessage(connection, ControlMessage.FileOffer(metadata.id, metadata.name, metadata.size))

            log("SEND", "waiting for FILE_ACCEPT")
            val responseFrame = readFrame(connection, "SEND") ?: return@withContext false
            log("SEND", "received frame type=${frameTypeName(responseFrame.first)}")
            if (responseFrame.first != FrameType.CONTROL_MESSAGE) {
                log("SEND", "expected CONTROL_MESSAGE while waiting for FILE_ACCEPT")
                return@withContext false
            }

            val response = json.decodeFromString<ControlMessage>(responseFrame.second.decodeToString())
            log("SEND", "received control message=${controlMessageName(response)}")
            if (response !is ControlMessage.FileAccept) {
                log("SEND", "transfer not accepted")
                return@withContext false
            }

            log("SEND", "starting chunks")
            val buffer = Buffer()
            var offset = 0L
            val hashingSource = HashingSource.sha256(source)
            while (offset < metadata.size) {
                val bytesToRead = minOf(chunkSize.toLong(), metadata.size - offset)
                val read = hashingSource.read(buffer, bytesToRead)
                check(read != -1L) { "Source ended at $offset bytes, expected ${metadata.size}" }
                check(read != 0L) { "Source returned 0 bytes before EOF at offset $offset" }

                val chunkData = buffer.readByteArray()
                val chunkPayload = ByteArray(8 + chunkData.size)
                for (i in 0..7) {
                    chunkPayload[i] = (offset shr ((7 - i) * 8)).toByte()
                }
                chunkData.copyInto(chunkPayload, destinationOffset = 8)
                writeFrame(connection, FrameType.CHUNK, chunkPayload)
                log("SEND", "chunk offset=$offset, size=${chunkData.size}, totalSent=${offset + chunkData.size}/${metadata.size}")
                offset += chunkData.size
                onProgress(offset, metadata.size)
            }

            val extraRead = hashingSource.read(Buffer(), 1)
            check(extraRead == -1L) { "Source contains more than declared size ${metadata.size}" }

            val hash = hashingSource.hash.hex()
            log("SEND", "sending COMPLETE bytesSent=$offset, hash=$hash")
            sendControlMessage(connection, ControlMessage.Complete(metadata.id, hash))

            log("SEND", "waiting for SUCCESS")
            val finalFrame = readFrame(connection, "SEND") ?: return@withContext false
            log("SEND", "final frame type=${frameTypeName(finalFrame.first)}")
            if (finalFrame.first != FrameType.CONTROL_MESSAGE) {
                log("SEND", "expected final CONTROL_MESSAGE")
                return@withContext false
            }
            val finalMessage = json.decodeFromString<ControlMessage>(finalFrame.second.decodeToString())
            log("SEND", "final message=${controlMessageName(finalMessage)}")
            finalMessage is ControlMessage.Success
        } catch (exception: Exception) {
            logException("SEND", exception)
            try {
                sendControlMessage(connection, ControlMessage.Error(metadata.id, exception.message ?: "Unknown error"))
            } catch (sendError: Exception) {
                logException("SEND", sendError)
            }
            false
        } finally {
            try {
                source?.close()
            } catch (closeError: Exception) {
                logException("SEND", closeError)
            }
        }
    }

    suspend fun receiveFile(
        connection: Connection,
        onOfferReceived: suspend (ControlMessage.FileOffer) -> IncomingFileDestination?,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        var hashingSink: HashingSink? = null
        var metadata: ControlMessage.FileOffer? = null
        var fileValid = false
        var activeDestination: IncomingFileDestination? = null

        try {
            log("RECEIVE", "waiting for FILE_OFFER")
            val offerFrame = readFrame(connection, "RECEIVE") ?: return@withContext false
            log("RECEIVE", "received frame type=${frameTypeName(offerFrame.first)}")
            if (offerFrame.first != FrameType.CONTROL_MESSAGE) {
                log("RECEIVE", "expected CONTROL_MESSAGE for FILE_OFFER")
                return@withContext false
            }

            val offer = json.decodeFromString<ControlMessage>(offerFrame.second.decodeToString())
            log("RECEIVE", "received control message=${controlMessageName(offer)}")
            if (offer !is ControlMessage.FileOffer) {
                log("RECEIVE", "first control message is not FILE_OFFER")
                return@withContext false
            }
            require(offer.name.isNotBlank()) { "Offered file name is blank" }
            require(offer.size >= 0) { "Offered file size is negative: ${offer.size}" }
            metadata = offer
            log("RECEIVE", "FILE_OFFER id=${offer.id}, name=${offer.name}, size=${offer.size}")

            val destination = onOfferReceived(offer)
            if (destination == null) {
                log("RECEIVE", "offer rejected by destination callback")
                sendControlMessage(connection, ControlMessage.FileReject(offer.id))
                return@withContext false
            }
            log("RECEIVE", "destination created; sending FILE_ACCEPT")
            sendControlMessage(connection, ControlMessage.FileAccept(offer.id))

            activeDestination = destination
            hashingSink = HashingSink.sha256(destination.openSink())
            val bufferedSink = hashingSink.buffer()
            var receivedBytes = 0L
            var complete: ControlMessage.Complete? = null

            while (receivedBytes < offer.size) {
                val frame = readFrame(connection, "RECEIVE") ?: return@withContext false
                log("RECEIVE", "received frame type=${frameTypeName(frame.first)}")
                if (frame.first == FrameType.CONTROL_MESSAGE) {
                    val message = json.decodeFromString<ControlMessage>(frame.second.decodeToString())
                    log("RECEIVE", "received control message=${controlMessageName(message)}")
                    when (message) {
                        is ControlMessage.Cancel, is ControlMessage.Error -> return@withContext false
                        is ControlMessage.Complete -> {
                            complete = message
                            log("RECEIVE", "received COMPLETE before advertised size: totalReceived=$receivedBytes/${offer.size}")
                            break
                        }
                        else -> error("Unexpected ${controlMessageName(message)} while receiving chunks")
                    }
                } else {
                    val payload = frame.second
                    require(payload.size >= 8) { "CHUNK payload is shorter than its offset" }
                    var offset = 0L
                    for (i in 0..7) {
                        offset = (offset shl 8) or (payload[i].toLong() and 0xFF)
                    }
                    val chunkData = payload.copyOfRange(8, payload.size)
                    require(offset == receivedBytes) { "Unexpected CHUNK offset $offset, expected $receivedBytes" }
                    require(receivedBytes + chunkData.size <= offer.size) {
                        "CHUNK exceeds advertised size: ${receivedBytes + chunkData.size} > ${offer.size}"
                    }
                    bufferedSink.write(chunkData)
                    bufferedSink.emit()
                    receivedBytes += chunkData.size
                    log("RECEIVE", "CHUNK offset=$offset, size=${chunkData.size}, totalReceived=$receivedBytes/${offer.size}")
                    onProgress(receivedBytes, offer.size)
                }
            }

            bufferedSink.flush()
            if (complete == null) {
                log("RECEIVE", "waiting for COMPLETE")
                val frame = readFrame(connection, "RECEIVE") ?: return@withContext false
                log("RECEIVE", "received frame type=${frameTypeName(frame.first)}")
                if (frame.first != FrameType.CONTROL_MESSAGE) {
                    log("RECEIVE", "expected CONTROL_MESSAGE for COMPLETE")
                    return@withContext false
                }
                val message = json.decodeFromString<ControlMessage>(frame.second.decodeToString())
                log("RECEIVE", "received control message=${controlMessageName(message)}")
                if (message !is ControlMessage.Complete) {
                    log("RECEIVE", "expected COMPLETE")
                    return@withContext false
                }
                complete = message
            }

            val hash = hashingSink.hash.hex()
            log("RECEIVE", "hash local=$hash, remote=${complete.hash}, totalReceived=$receivedBytes/${offer.size}")
            if (receivedBytes == offer.size && hash.equals(complete.hash, ignoreCase = true)) {
                log("RECEIVE", "sending SUCCESS")
                sendControlMessage(connection, ControlMessage.Success(offer.id))
                fileValid = true
                true
            } else {
                log("RECEIVE", "sending HASH_MISMATCH")
                sendControlMessage(connection, ControlMessage.HashMismatch(offer.id))
                false
            }
        } catch (exception: Exception) {
            logException("RECEIVE", exception)
            metadata?.let { offer ->
                try {
                    sendControlMessage(connection, ControlMessage.Error(offer.id, exception.message ?: "Unknown error"))
                } catch (sendError: Exception) {
                    logException("RECEIVE", sendError)
                }
            }
            false
        } finally {
            try {
                hashingSink?.close()
            } catch (closeError: Exception) {
                logException("RECEIVE", closeError)
            }
            if (fileValid) {
                try {
                    activeDestination?.commit()
                } catch (commitError: Exception) {
                    logException("RECEIVE", commitError)
                }
            } else {
                try {
                    activeDestination?.abort()
                } catch (abortError: Exception) {
                    logException("RECEIVE", abortError)
                }
            }
        }
    }
}
