package com.fastdrop.transfer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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
        var tempName = "unknown"
        var tempSize = 0L
        
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                
                if (nameIndex != -1) {
                    tempName = cursor.getString(nameIndex) ?: "unknown"
                }
                if (sizeIndex != -1) {
                    tempSize = cursor.getLong(sizeIndex)
                }
            }
        }
        
        name = tempName
        size = tempSize
    }

    override suspend fun openSource(): Source {
        val inputStream = context.contentResolver.openInputStream(uri) 
            ?: throw FileNotFoundException("Could not open URI: $uri")
        return inputStream.source()
    }
}
