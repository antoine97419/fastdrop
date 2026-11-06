package com.fastdrop.transfer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidTransferFileSourceTest {
    @Test
    fun openableColumnsSizeCorrect() {
        var streamMeasured = false

        val size = selectReliableAndroidFileSize(
            displaySize = 33,
            statSize = 33,
            assetSize = 33
        ) {
            streamMeasured = true
            33
        }

        assertEquals(33L, size)
        assertFalse(streamMeasured)
    }

    @Test
    fun staleOpenableColumnsSizeUsesStatSize() {
        val size = selectReliableAndroidFileSize(
            displaySize = 21,
            statSize = 33,
            assetSize = 33
        ) { error("Stream should not be measured when statSize is known") }

        assertEquals(33L, size)
    }

    @Test
    fun missingMetadataUsesDescriptorSize() {
        val size = selectReliableAndroidFileSize(
            displaySize = null,
            statSize = 33,
            assetSize = null
        ) { error("Stream should not be measured when statSize is known") }

        assertEquals(33L, size)
    }

    @Test
    fun unknownDescriptorSizesFallBackToStream() {
        var streamMeasured = false

        val size = selectReliableAndroidFileSize(
            displaySize = 21,
            statSize = -1,
            assetSize = -1
        ) {
            streamMeasured = true
            33
        }

        assertEquals(33L, size)
        assertTrue(streamMeasured)
    }

    @Test
    fun emptyFileSizeIsValid() {
        val size = selectReliableAndroidFileSize(
            displaySize = 0,
            statSize = 0,
            assetSize = 0
        ) { error("Stream should not be measured for a known empty file") }

        assertEquals(0L, size)
    }

    @Test
    fun realSizeGreaterThanMetadataUsesRealSize() {
        val size = selectReliableAndroidFileSize(
            displaySize = 21,
            statSize = 33,
            assetSize = null
        ) { error("Stream should not be measured when statSize is known") }

        assertEquals(33L, size)
    }
}
