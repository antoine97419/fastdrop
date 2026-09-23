package com.fastdrop.transfer

import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.Sink
import okio.Source
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class DesktopTransferFileSource(private val file: File) : TransferFileSource {
    override val name: String = file.name
    override val size: Long = file.length()
    override suspend fun openSource(): Source = FileSystem.SYSTEM.source(file.toOkioPath())
}

class DesktopIncomingFileDestination(private val targetFile: File) : IncomingFileDestination {
    private val tempFile = File("${targetFile.absolutePath}.fastdrop-part")
    
    override suspend fun openSink(): Sink {
        targetFile.parentFile?.mkdirs()
        return FileSystem.SYSTEM.sink(tempFile.toOkioPath())
    }

    override suspend fun commit() {
        if (tempFile.exists()) {
            Files.move(tempFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    override suspend fun abort() {
        if (tempFile.exists()) {
            tempFile.delete()
        }
    }
}
