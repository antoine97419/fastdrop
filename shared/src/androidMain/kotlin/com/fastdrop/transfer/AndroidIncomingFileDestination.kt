package com.fastdrop.transfer

import android.content.Context
import android.os.Environment
import okio.Sink
import okio.sink
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class AndroidIncomingFileDestination(
    context: Context,
    filename: String
) : IncomingFileDestination {
    
    // Fallback à filesDir si getExternalFilesDir est null
    private val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
    
    private val targetFile = File(directory, filename)
    private val tempFile = File(directory, "$filename.fastdrop-part")
    
    val finalFile: File get() = targetFile

    override suspend fun openSink(): Sink {
        directory.mkdirs()
        return tempFile.sink()
    }

    override suspend fun commit() {
        if (tempFile.exists()) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                Files.move(tempFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } else {
                if (targetFile.exists()) targetFile.delete()
                tempFile.renameTo(targetFile)
            }
        }
    }

    override suspend fun abort() {
        if (tempFile.exists()) {
            tempFile.delete()
        }
    }
}
