package com.fastdrop

import java.util.Locale
import kotlin.math.roundToInt

data class DesktopProgress(val fraction: Float, val label: String)

/** Text and progress derived from runtime state; Compose only renders them. */
object DesktopRuntimePresentation {
    fun message(status: DesktopStatus): String = when (status) {
        DesktopStatus.Starting -> "Searching for nearby devices…"
        DesktopStatus.Ready -> "Ready"
        is DesktopStatus.Connecting -> "Connecting to ${status.peer}…"
        is DesktopStatus.WaitingForDevice -> "Waiting for device…"
        is DesktopStatus.Authenticating -> "Authenticating ${status.peer}…"
        is DesktopStatus.WaitingForApproval -> "Waiting for approval…"
        is DesktopStatus.Sending -> "Sending ${status.file}"
        is DesktopStatus.Receiving -> if (status.current == 0L)
            "Incoming file from ${status.peerName}" else "Receiving ${status.file}"
        is DesktopStatus.Completed -> "Transfer complete"
        DesktopStatus.Rejected -> "Transfer rejected"
        DesktopStatus.Busy -> "Device busy"
        DesktopStatus.IntegrityFailed -> "File integrity check failed"
        DesktopStatus.ConnectionLost -> "Connection lost"
        is DesktopStatus.Error -> status.message
    }

    fun progress(status: DesktopStatus): DesktopProgress? {
        val (current, total) = when (status) {
            is DesktopStatus.Sending -> status.current to status.total
            is DesktopStatus.Receiving -> status.current to status.total
            else -> return null
        }
        val fraction = if (total <= 0) 0f else
            (current.toDouble() / total).toFloat().coerceIn(0f, 1f)
        val percent = (fraction * 100).roundToInt()
        return DesktopProgress(fraction,
            "$percent% — ${bytes(current)} / ${bytes(total)}")
    }

    private fun bytes(value: Long): String = when {
        value >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", value / (1024.0 * 1024))
        value >= 1024 -> String.format(Locale.US, "%.1f KB", value / 1024.0)
        else -> "$value B"
    }
}
