package com.fastdrop.android

import kotlinx.coroutines.CancellationException

internal enum class LegacySendResult {
    CANCELLED,
    SENT,
    FAILED
}

internal class PickerFirstLegacySendWorkflow<FileRef : Any, RawConnection : Any, SecureConnection : Any>(
    private val openPicker: suspend () -> Unit,
    private val connect: suspend () -> RawConnection,
    private val authenticate: suspend (RawConnection) -> SecureConnection,
    private val send: suspend (SecureConnection, FileRef) -> Boolean,
    private val closeSecure: suspend (SecureConnection) -> Unit,
    private val closeRaw: suspend (RawConnection) -> Unit,
    private val updateState: suspend (String) -> Unit,
    private val reportCloseError: (Throwable) -> Unit = { it.printStackTrace() },
    private val reportFailure: (Throwable) -> Unit = { it.printStackTrace() }
) {
    suspend fun startSelection() {
        updateState(SELECTING_FILE)
        openPicker()
    }

    suspend fun onPickerResult(file: FileRef?): LegacySendResult {
        if (file == null) {
            updateState(READY)
            return LegacySendResult.CANCELLED
        }

        var rawConnection: RawConnection? = null
        var secureConnection: SecureConnection? = null

        return try {
            updateState(CONNECTING)
            rawConnection = connect()

            updateState(AUTHENTICATING)
            secureConnection = authenticate(rawConnection)

            updateState(SENDING)
            if (send(secureConnection, file)) {
                updateState(SENT)
                LegacySendResult.SENT
            } else {
                updateState(SEND_FAILED)
                LegacySendResult.FAILED
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            reportFailure(exception)
            updateState("Error: ${exception.message ?: exception::class.simpleName}")
            LegacySendResult.FAILED
        } finally {
            try {
                if (secureConnection != null) {
                    closeSecure(secureConnection)
                } else if (rawConnection != null) {
                    closeRaw(rawConnection)
                }
            } catch (closeError: Exception) {
                reportCloseError(closeError)
            }
        }
    }

    companion object {
        const val READY = "Ready"
        const val SELECTING_FILE = "Selecting file..."
        const val CONNECTING = "Connecting to Windows..."
        const val AUTHENTICATING = "Authenticating..."
        const val SENDING = "Sending file..."
        const val SENT = "Sent successfully!"
        const val SEND_FAILED = "Send failed."
    }
}
