package com.fastdrop

import com.fastdrop.transfer.TransferOutcome
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopPresentationTest {
    @Test
    fun transferResultsHaveDistinctUserMessages() {
        assertEquals("Transfer rejected by receiver", DesktopPresentation.message(DesktopPresentation.fromOutcome(TransferOutcome.REJECTED)))
        assertEquals("File integrity check failed", DesktopPresentation.message(DesktopPresentation.fromOutcome(TransferOutcome.HASH_MISMATCH)))
        assertEquals("Connection lost", DesktopPresentation.message(DesktopPresentation.fromOutcome(TransferOutcome.CONNECTION_LOST)))
        assertEquals("Transfer failed", DesktopPresentation.message(DesktopPresentation.fromOutcome(TransferOutcome.FAILED)))
        assertEquals("Transfer complete", DesktopPresentation.message(DesktopPresentation.fromOutcome(TransferOutcome.SUCCESS)))
    }

    @Test
    fun progressShowsFileAndPercentage() {
        assertEquals("Sending photo.jpg — 42%", DesktopPresentation.message(DesktopUiStatus.Sending("photo.jpg", 42)))
    }
}
