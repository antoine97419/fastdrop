package com.fastdrop

import com.fastdrop.transfer.TransferOutcome

internal sealed interface DesktopUiStatus {
    data object ReadyToReceive : DesktopUiStatus
    data object WaitingForDevice : DesktopUiStatus
    data class Sending(val file: String, val percent: Int) : DesktopUiStatus
    data object Complete : DesktopUiStatus
    data object Rejected : DesktopUiStatus
    data object IntegrityFailed : DesktopUiStatus
    data object ConnectionLost : DesktopUiStatus
    data object Failed : DesktopUiStatus
}

internal object DesktopPresentation {
    fun fromOutcome(outcome: TransferOutcome): DesktopUiStatus = when (outcome) {
        TransferOutcome.SUCCESS -> DesktopUiStatus.Complete
        TransferOutcome.REJECTED -> DesktopUiStatus.Rejected
        TransferOutcome.HASH_MISMATCH -> DesktopUiStatus.IntegrityFailed
        TransferOutcome.CONNECTION_LOST -> DesktopUiStatus.ConnectionLost
        TransferOutcome.FAILED -> DesktopUiStatus.Failed
    }

    fun message(status: DesktopUiStatus): String = when (status) {
        DesktopUiStatus.ReadyToReceive -> "Ready to receive"
        DesktopUiStatus.WaitingForDevice -> "Waiting for device…"
        is DesktopUiStatus.Sending -> "Sending " + status.file + " — " + status.percent + "%"
        DesktopUiStatus.Complete -> "Transfer complete"
        DesktopUiStatus.Rejected -> "Transfer rejected by receiver"
        DesktopUiStatus.IntegrityFailed -> "File integrity check failed"
        DesktopUiStatus.ConnectionLost -> "Connection lost"
        DesktopUiStatus.Failed -> "Transfer failed"
    }
}
