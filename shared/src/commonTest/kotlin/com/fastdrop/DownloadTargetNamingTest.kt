package com.fastdrop.transfer

import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadTargetNamingTest {
    @Test
    fun destinationIsPublicFastDropDownloads() {
        assertEquals("Download/FastDrop/", DownloadTargetNaming.DISPLAY_DIRECTORY)
        assertEquals("Download/FastDrop/", DownloadTargetNaming.RELATIVE_DIRECTORY)
    }

    @Test
    fun existingNamesReceiveNumberedSuffixes() {
        val existing = setOf("photo.jpg", "photo (1).jpg")
        assertEquals("photo (2).jpg", DownloadTargetNaming.uniqueName("photo.jpg") { it in existing })
    }

    @Test
    fun fileWithoutExtensionCanBeNumbered() {
        assertEquals("empty (1)", DownloadTargetNaming.uniqueName("empty") { it == "empty" })
    }
}
