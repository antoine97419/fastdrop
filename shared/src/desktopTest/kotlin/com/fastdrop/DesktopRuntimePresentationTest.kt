package com.fastdrop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DesktopRuntimePresentationTest {
    @Test fun statesHaveClearMessages() {
        assertEquals("Ready", DesktopRuntimePresentation.message(DesktopStatus.Ready))
        assertEquals("Connecting to Pixel…", DesktopRuntimePresentation.message(DesktopStatus.Connecting("Pixel")))
        assertEquals("Waiting for device…", DesktopRuntimePresentation.message(DesktopStatus.WaitingForDevice("file.bin")))
        assertEquals("Waiting for approval…", DesktopRuntimePresentation.message(DesktopStatus.WaitingForApproval("file.bin")))
        assertEquals("Sending file.bin", DesktopRuntimePresentation.message(DesktopStatus.Sending("file.bin", 1, 10)))
        assertEquals("Incoming file from Pixel", DesktopRuntimePresentation.message(
            DesktopStatus.Receiving("file.bin", 0, 10, "Pixel")))
        assertEquals("Transfer complete", DesktopRuntimePresentation.message(DesktopStatus.Completed("file.bin")))
        assertEquals("Transfer rejected", DesktopRuntimePresentation.message(DesktopStatus.Rejected))
        assertEquals("Device busy", DesktopRuntimePresentation.message(DesktopStatus.Busy))
        assertEquals("Connection lost", DesktopRuntimePresentation.message(DesktopStatus.ConnectionLost))
        assertEquals("File is unavailable", DesktopRuntimePresentation.message(DesktopStatus.Error("File is unavailable")))
    }

    @Test fun progressIsBoundedAndReadable() {
        val progress = assertNotNull(DesktopRuntimePresentation.progress(
            DesktopStatus.Sending("file.bin", 4_404_019, 10_485_760)))
        assertEquals(0.42f, progress.fraction, 0.01f)
        assertEquals("42% — 4.2 MB / 10.0 MB", progress.label)
    }
}
