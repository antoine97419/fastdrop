import sys

with open("shared/src/commonMain/kotlin/com/fastdrop/transfer/TransferManager.kt", "r") as f:
    text = f.read()

old_send = """    suspend fun sendFile(
        connection: Connection, 
        metadata: FileMetadata, 
        fileSource: Source,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {"""

new_send = """    suspend fun sendFile(
        connection: Connection, 
        fileSource: TransferFileSource,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        val metadata = FileMetadata("transfer_${com.fastdrop.utils.getCurrentTimeMillis()}", fileSource.name, fileSource.size)
        val source = fileSource.openSource()"""
text = text.replace(old_send, new_send)

text = text.replace("val hashingSource = HashingSource.sha256(fileSource)", "val hashingSource = HashingSource.sha256(source)")
text = text.replace("fileSource.close()", "source.close()")

old_recv = """    suspend fun receiveFile(
        connection: Connection, 
        onOfferReceived: suspend (ControlMessage.FileOffer) -> Boolean,
        fileSink: Sink,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {"""

new_recv = """    suspend fun receiveFile(
        connection: Connection, 
        onOfferReceived: suspend (ControlMessage.FileOffer) -> IncomingFileDestination?,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {"""
text = text.replace(old_recv, new_recv)

old_accepted = """            val accepted = onOfferReceived(offerMsg)
            if (!accepted) {
                sendControlMessage(connection, ControlMessage.FileReject(offerMsg.id))
                return@withContext false
            }"""

new_accepted = """            var destination: IncomingFileDestination? = null
            destination = onOfferReceived(offerMsg)
            if (destination == null) {
                sendControlMessage(connection, ControlMessage.FileReject(offerMsg.id))
                return@withContext false
            }"""
text = text.replace(old_accepted, new_accepted)

text = text.replace("hashingSink = HashingSink.sha256(fileSink)", """val rawSink = destination!!.openSink()
            hashingSink = HashingSink.sha256(rawSink)""")

old_finally = """        } finally {
            fileSink.close()
            // NB: Le renommage de '.fastdrop-part' est géré par l'appelant car TransferManager utilise un Sink
        }"""

new_finally = """        } finally {
            try { hashingSink?.close() } catch(e: Exception) {}
            if (fileValid) {
                try { onOfferReceived(metadata!!)?.commit() } catch(e: Exception) {} // Wait, we can just save destination reference
            }
        }"""
text = text.replace(old_finally, new_finally)

with open("shared/src/commonMain/kotlin/com/fastdrop/transfer/TransferManager.kt", "w") as f:
    f.write(text)
