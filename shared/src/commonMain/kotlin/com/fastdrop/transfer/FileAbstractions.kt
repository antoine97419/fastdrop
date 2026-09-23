package com.fastdrop.transfer

import okio.Sink
import okio.Source

interface TransferFileSource {
    val name: String
    val size: Long
    suspend fun openSource(): Source
}

interface IncomingFileDestination {
    suspend fun openSink(): Sink
    suspend fun commit()
    suspend fun abort()
}
