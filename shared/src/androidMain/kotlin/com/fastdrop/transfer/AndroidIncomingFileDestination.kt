package com.fastdrop.transfer

import android.annotation.TargetApi
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import okio.Sink
import okio.sink
import java.io.File
import java.util.Locale

/** A download remains pending until TransferManager verifies its SHA-256 hash. */
class AndroidIncomingFileDestination(
    context: Context,
    filename: String
) : IncomingFileDestination {
    private val resolver = context.applicationContext.contentResolver
    private val safeName = TransferManager().sanitizeFilename(filename)
    private val relativePath = DownloadTargetNaming.RELATIVE_DIRECTORY
    private val legacyDirectory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "FastDrop")
    private var uri: android.net.Uri? = null
    private var legacyPart: File? = null
    private var legacyTarget: File? = null
    private var chosenName: String? = null

    val displayPath: String get() = DownloadTargetNaming.DISPLAY_DIRECTORY + (chosenName ?: safeName)

    override suspend fun openSink(): Sink {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val name = DownloadTargetNaming.uniqueName(safeName) { candidate -> existsInMediaStore(candidate) }
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(Locale.ROOT))
                ?: "application/octet-stream"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val created = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create pending download")
            uri = created
            chosenName = name
            try {
                return (resolver.openOutputStream(created, "w") ?: error("Could not open pending download")).sink()
            } catch (failure: Exception) {
                resolver.delete(created, null, null)
                uri = null
                throw failure
            }
        }

        legacyDirectory.mkdirs()
        val name = DownloadTargetNaming.uniqueName(safeName) { candidate ->
            File(legacyDirectory, candidate).exists() || File(legacyDirectory, candidate + ".fastdrop-part").exists()
        }
        val target = File(legacyDirectory, name)
        val part = File(legacyDirectory, name + ".fastdrop-part")
        chosenName = name
        legacyTarget = target
        legacyPart = part
        return part.sink()
    }

    override suspend fun commit() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pending = uri ?: error("No pending download")
            val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            check(resolver.update(pending, values, null, null) == 1) { "Could not publish download" }
            uri = null
        } else {
            val part = legacyPart ?: error("No partial download")
            val target = legacyTarget ?: error("No target download")
            check(!target.exists() && part.renameTo(target)) { "Could not publish download" }
            legacyPart = null
        }
    }

    override suspend fun abort() {
        uri?.let { resolver.delete(it, null, null) }
        uri = null
        legacyPart?.delete()
        legacyPart = null
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private fun existsInMediaStore(name: String): Boolean {
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            MediaStore.Downloads.RELATIVE_PATH + "=? AND " + MediaStore.Downloads.DISPLAY_NAME + "=?",
            arrayOf(relativePath, name),
            null
        )?.use { return it.moveToFirst() }
        return false
    }
}
