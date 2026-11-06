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
        var displaySize: Long? = null
        
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                
                if (nameIndex != -1) {
                    tempName = cursor.getString(nameIndex) ?: "unknown"
                }
                if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                    displaySize = cursor.getLong(sizeIndex).takeIf { it >= 0 }
                }
            }
        }

        val statSize = try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.statSize.takeIf { it >= 0 }
            }
        } catch (exception: Exception) {
            Log.w("FastDrop", "AndroidTransferFileSource: could not read statSize for $uri", exception)
            null
        }

        val assetSize = try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.length.takeIf { it >= 0 }
            }
        } catch (exception: Exception) {
            Log.w("FastDrop", "AndroidTransferFileSource: could not read asset length for $uri", exception)
            null
        }

        var streamedSize: Long? = null
        val selectedSize = selectReliableAndroidFileSize(displaySize, statSize, assetSize) {
            streamedSize = try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        total += read
                    }
                    total
                }
            } catch (exception: Exception) {
                Log.w("FastDrop", "AndroidTransferFileSource: could not measure stream for $uri", exception)
                null
            }
            streamedSize
        } ?: throw FileNotFoundException("Could not determine size for URI: $uri")
        
        name = tempName
        size = selectedSize

        val availableSizes = listOfNotNull(displaySize, statSize, assetSize, streamedSize).distinct()
        val logMessage = "AndroidTransferFileSource: displaySize=${displaySize ?: "unknown"}, " +
            "statSize=${statSize ?: "unknown"}, assetSize=${assetSize ?: "unknown"}, " +
            "streamedSize=${streamedSize ?: "not-needed"}, selectedSize=$size, name=$name, uri=$uri"
        if (availableSizes.size > 1) {
            Log.w("FastDrop", "Divergent file sizes: $logMessage")
        } else {
            Log.d("FastDrop", logMessage)
        }
    }

    override suspend fun openSource(): Source {
        val inputStream = context.contentResolver.openInputStream(uri) 
            ?: throw FileNotFoundException("Could not open URI: $uri")
        return inputStream.source()
    }
}

internal fun selectReliableAndroidFileSize(
    displaySize: Long?,
    statSize: Long?,
    assetSize: Long?,
    streamSize: () -> Long?
): Long? {
    statSize?.takeIf { it >= 0 }?.let { return it }
    assetSize?.takeIf { it >= 0 }?.let { return it }
    streamSize()?.takeIf { it >= 0 }?.let { return it }
    return displaySize?.takeIf { it >= 0 }
}
