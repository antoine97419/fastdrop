package com.fastdrop.android

import com.fastdrop.discovery.DiscoveredPeer
import com.fastdrop.discovery.DiscoveryType
import com.fastdrop.transfer.TransferOutcome
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FastDropUiPresentationTest {
    private fun peer(name: String, verified: Boolean, id: String = name) = DiscoveredPeer(
        discoveryId = id,
        displayName = name,
        addresses = listOf("192.168.137.1"),
        port = 47832,
        source = DiscoveryType.WIFI_DIRECT,
        isFastDropService = verified
    )

    @Test
    fun excludesRawWifiDevicesAndPrinters() {
        val cards = FastDropUiPresentation.devices(
            listOf(peer("DIRECT-E6-HP DeskJet", false), peer("Google Pixel 7a ?", true))
        )
        assertEquals(listOf("Google Pixel 7a"), cards.map { it.name })
    }

    @Test
    fun trustedAndAvailableCardsRemainDistinct() {
        val cards = FastDropUiPresentation.devices(
            listOf(peer("Google Pixel 7a ?", true), peer("Windows PC", true)),
            setOf("Google Pixel 7a")
        )
        assertEquals(true, cards[0].trusted)
        assertEquals(false, cards[1].trusted)
    }

    @Test
    fun mapsWorkflowStatesWithoutTechnicalErrors() {
        assertIs<FastDropUiStatus.Searching>(FastDropUiPresentation.fromWorkflow("Ready", false))
        assertIs<FastDropUiStatus.Selecting>(FastDropUiPresentation.fromWorkflow("Selecting file...", false))
        assertIs<FastDropUiStatus.Connecting>(FastDropUiPresentation.fromWorkflow("Connecting to Windows...", false))
        assertIs<FastDropUiStatus.Authenticating>(FastDropUiPresentation.fromWorkflow("Authenticating...", false))
        assertIs<FastDropUiStatus.WaitingForApproval>(FastDropUiPresentation.fromWorkflow("Waiting for SAS confirmation...", false))
        assertIs<FastDropUiStatus.Rejected>(FastDropUiPresentation.fromWorkflow("File rejected.", false))
        assertIs<FastDropUiStatus.Sent>(FastDropUiPresentation.fromWorkflow("Sent successfully!", false))
        assertIs<FastDropUiStatus.Received>(FastDropUiPresentation.fromWorkflow("Received successfully!", false))
        assertIs<FastDropUiStatus.Sending>(FastDropUiPresentation.fromWorkflow("Sending file...", false))
        assertIs<FastDropUiStatus.Receiving>(FastDropUiPresentation.fromWorkflow("Receiving file...", false))
        assertIs<FastDropUiStatus.IntegrityFailed>(FastDropUiPresentation.fromWorkflow("File integrity check failed", false))
        assertIs<FastDropUiStatus.Failed>(FastDropUiPresentation.fromWorkflow("Error: raw exception", false))
        assertIs<FastDropUiStatus.ConnectionLost>(FastDropUiPresentation.error(IOException("reset")))
    }

    @Test
    fun senderOutcomesRemainDistinct() {
        assertEquals("Transfer rejected", FastDropUiPresentation.outcomeLabel(TransferOutcome.REJECTED))
        assertEquals("File integrity check failed", FastDropUiPresentation.outcomeLabel(TransferOutcome.HASH_MISMATCH))
        assertEquals("Connection lost", FastDropUiPresentation.outcomeLabel(TransferOutcome.CONNECTION_LOST))
        assertEquals("Sent successfully!", FastDropUiPresentation.outcomeLabel(TransferOutcome.SUCCESS))
    }

    @Test
    fun progressIsClampedAndHumanReadable() {
        val progress = FastDropUiStatus.Transferring(true, "photo.jpg", "Windows PC", 4_404_019, 10_485_760)
        assertEquals(42, progress.percent)
        assertEquals("4.2 MB", FastDropUiPresentation.bytes(progress.current))
        assertEquals("10.0 MB", FastDropUiPresentation.bytes(progress.total))
    }
}
