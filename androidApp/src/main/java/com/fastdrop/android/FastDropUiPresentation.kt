package com.fastdrop.android

import com.fastdrop.discovery.DiscoveredPeer
import com.fastdrop.transfer.TransferOutcome
import java.io.IOException
import java.util.Locale
import kotlin.math.roundToInt

internal data class FastDropDeviceCard(
    val peer: DiscoveredPeer,
    val name: String,
    val trusted: Boolean
)

internal sealed interface FastDropUiStatus {
    data object Idle : FastDropUiStatus
    data object Searching : FastDropUiStatus
    data object Selecting : FastDropUiStatus
    data object Connecting : FastDropUiStatus
    data object Authenticating : FastDropUiStatus
    data object WaitingForApproval : FastDropUiStatus
    data object WaitingForFile : FastDropUiStatus
    data object Sending : FastDropUiStatus
    data object Receiving : FastDropUiStatus
    data class Transferring(
        val sending: Boolean,
        val fileName: String,
        val peerName: String,
        val current: Long,
        val total: Long
    ) : FastDropUiStatus {
        val percent: Int get() = if (total <= 0) 100 else
            (current.coerceIn(0, total).toDouble() / total * 100).roundToInt().coerceIn(0, 100)
    }
    data object Sent : FastDropUiStatus
    data object Received : FastDropUiStatus
    data object Complete : FastDropUiStatus
    data object Rejected : FastDropUiStatus
    data object ConnectionLost : FastDropUiStatus
    data object IntegrityFailed : FastDropUiStatus
    data object Failed : FastDropUiStatus
}

internal object FastDropUiPresentation {
    fun devices(peers: List<DiscoveredPeer>, trustedNames: Set<String> = emptySet()): List<FastDropDeviceCard> =
        peers.asSequence()
            .filter { it.isFastDropService }
            .distinctBy { it.discoveryId }
            .map { peer ->
                val name = cleanName(peer.displayName)
                FastDropDeviceCard(peer, name, name in trustedNames)
            }
            .toList()

    fun cleanName(name: String?): String =
        name?.trim()?.trimEnd('?', ' ')?.takeIf { it.isNotBlank() } ?: "FastDrop device"

    fun fromWorkflow(raw: String, hasDevices: Boolean): FastDropUiStatus = when {
        raw == "Ready" -> if (hasDevices) FastDropUiStatus.Idle else FastDropUiStatus.Searching
        raw.startsWith("Selecting") -> FastDropUiStatus.Selecting
        raw.startsWith("Connecting") -> FastDropUiStatus.Connecting
        raw.startsWith("Authenticating") -> FastDropUiStatus.Authenticating
        raw.contains("SAS", ignoreCase = true) -> FastDropUiStatus.WaitingForApproval
        raw.startsWith("Waiting for file") -> FastDropUiStatus.WaitingForFile
        raw.startsWith("Sending") -> FastDropUiStatus.Sending
        raw.startsWith("Receiving") -> FastDropUiStatus.Receiving
        raw.startsWith("Incoming connection") -> FastDropUiStatus.Connecting
        raw.startsWith("Received successfully") -> FastDropUiStatus.Received
        raw.startsWith("Sent successfully") -> FastDropUiStatus.Sent
        raw.startsWith("Transfer success") -> FastDropUiStatus.Complete
        raw.contains("reject", ignoreCase = true) -> FastDropUiStatus.Rejected
        raw.contains("integrity", ignoreCase = true) -> FastDropUiStatus.IntegrityFailed
        raw.contains("lost", ignoreCase = true) || raw.contains("unavailable", ignoreCase = true) ||
            raw.contains("connection reset", ignoreCase = true) || raw.contains("socket", ignoreCase = true) ||
            raw.contains("network", ignoreCase = true) ->
            FastDropUiStatus.ConnectionLost
        raw.startsWith("Error:") || raw.contains("fail", ignoreCase = true) -> FastDropUiStatus.Failed
        else -> FastDropUiStatus.Idle
    }

    fun outcomeLabel(outcome: TransferOutcome): String = when (outcome) {
        TransferOutcome.SUCCESS -> "Sent successfully!"
        TransferOutcome.REJECTED -> "Transfer rejected"
        TransferOutcome.HASH_MISMATCH -> "File integrity check failed"
        TransferOutcome.CONNECTION_LOST -> "Connection lost"
        TransferOutcome.FAILED -> "Transfer failed"
    }

    fun error(error: Throwable): FastDropUiStatus =
        if (error is IOException || error.message?.contains("network", ignoreCase = true) == true ||
            error.message?.contains("socket", ignoreCase = true) == true ||
            error.message?.contains("connection", ignoreCase = true) == true
        ) FastDropUiStatus.ConnectionLost else FastDropUiStatus.Failed

    fun bytes(value: Long): String = when {
        value >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", value / (1024.0 * 1024))
        value >= 1024 -> String.format(Locale.US, "%.1f KB", value / 1024.0)
        else -> "$value B"
    }
}
