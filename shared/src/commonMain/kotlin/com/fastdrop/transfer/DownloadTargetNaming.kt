package com.fastdrop.transfer

/** Names and logical location of downloads published by the Android receiver. */
object DownloadTargetNaming {
    const val DISPLAY_DIRECTORY = "Download/FastDrop/"
    const val RELATIVE_DIRECTORY = "Download/FastDrop/"

    fun uniqueName(safeName: String, exists: (String) -> Boolean): String {
        val dot = safeName.lastIndexOf('.')
        val base = if (dot > 0) safeName.substring(0, dot) else safeName
        val extension = if (dot > 0) safeName.substring(dot) else ""
        var index = 0
        while (true) {
            val candidate = if (index == 0) safeName else base + " (" + index + ")" + extension
            if (!exists(candidate)) return candidate
            index++
        }
    }
}
