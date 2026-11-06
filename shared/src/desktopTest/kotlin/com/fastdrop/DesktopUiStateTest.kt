package com.fastdrop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DesktopUiStateTest {
    @Test fun typedStatesCoverWindowWorkflow() {
        var ui = DesktopUiState()
        assertEquals(DesktopStatus.Starting, ui.status)
        ui = ui.copy(status = DesktopStatus.Ready, peers = listOf(
            NearbyPeer("public-id", "Google Pixel 7a", false, setOf("send", "receive"))))
        assertEquals(DesktopStatus.Ready, ui.status)
        assertEquals(false, ui.peers.single().trusted)
        ui = ui.copy(peers = listOf(ui.peers.single().copy(trusted = true)),
            sas = SasRequest("Google Pixel 7a", "657328"))
        assertEquals(true, ui.peers.single().trusted)
        assertEquals("657328", ui.sas?.code)
        assertIs<DesktopStatus.Sending>(DesktopStatus.Sending("photo.jpg", 42, 100))
        assertIs<DesktopStatus.Receiving>(DesktopStatus.Receiving("photo.jpg", 42, 100))
        assertIs<DesktopStatus.Completed>(DesktopStatus.Completed("photo.jpg"))
        assertEquals(DesktopStatus.Rejected, DesktopStatus.Rejected)
        assertEquals(DesktopStatus.ConnectionLost, DesktopStatus.ConnectionLost)
    }
}
