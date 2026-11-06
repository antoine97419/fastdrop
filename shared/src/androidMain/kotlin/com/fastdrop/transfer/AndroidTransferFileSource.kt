package com.fastdrop.transfer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import okio.Source
import okio.source
import java.io.FileNotFoundException

class AndroidTransferFileSource(
    private val context: Context,
    private val uri: Uri
) : TransferFileSource {
    
    override val name: String
    override val size: Long
    
    init {
        var tempName = uri.lastPathSegment ?: "unknown"
        var tempSize = -1L
        
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                
                if (nameIndex != -1) {
                    tempName = cursor.getString(nameIndex) ?: "unknown"
                }
                if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                    tempSize = cursor.getLong(sizeIndex)
                }
            }
        }

        if (tempSize < 0) {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                if (descriptor.length >= 0) {
                    tempSize = descriptor.length
                }
            }
        }

        if (tempSize < 0) {
            tempSize = context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    total += read
                }
                total
            } ?: throw FileNotFoundException("Could not determine size for URI: $uri")
        }
        
        name = tempName
        size = tempSize
        Log.d("FastDrop", "AndroidTransferFileSource: uri=$uri, name=$name, size=$size")
    }

    override suspend fun openSource(): Source {
        val inputStream = context.contentResolver.openInputStream(uri) 
            ?: throw FileNotFoundException("Could not open URI: $uri")
        return inputStream.source()
    }
}
