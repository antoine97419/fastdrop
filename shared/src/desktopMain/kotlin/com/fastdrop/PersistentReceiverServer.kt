package com.fastdrop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

internal class PersistentReceiverServer<RawConnection : Any, SecureConnection : Any>(
    private val startHosting: suspend () -> Flow<RawConnection>,
    private val createSecureChannel: (RawConnection) -> SecureConnection,
    private val authenticate: suspend (SecureConnection) -> Boolean,
    private val receiveTransfer: suspend (SecureConnection) -> Boolean,
    private val closeSecureChannel: suspend (SecureConnection) -> Unit,
    private val closeRawConnection: suspend (RawConnection) -> Unit,
    private val stopHosting: suspend () -> Unit,
    private val onListening: () -> Unit,
    private val onSessionMessage: (String) -> Unit,
    private val onCloseError: (Throwable) -> Unit = { it.printStackTrace() }
) {
    @Volatile
    private var stopping = false

    @Volatile
    private var listenerJob: Job? = null

    suspend fun run() {
        check(listenerJob == null) { "Receiver is already running" }
        stopping = false
        listenerJob = currentCoroutineContext()[Job]

        try {
            onListening()
            coroutineScope {
                startHosting().collect { rawConnection ->
                    if (!stopping) launch { handleClient(rawConnection) }
                    else closeSafely { closeRawConnection(rawConnection) }
                }
            }
        } catch (cancellation: CancellationException) {
            if (!stopping) throw cancellation
        } finally {
            listenerJob = null
        }
    }

    suspend fun stop() {
        if (stopping) return
        stopping = true

        try {
            stopHosting()
        } finally {
            listenerJob?.cancel()
        }
    }

    private suspend fun handleClient(rawConnection: RawConnection) {
        var secureChannel: SecureConnection? = null

        try {
            secureChannel = createSecureChannel(rawConnection)
            if (!authenticate(secureChannel)) {
                onSessionMessage("Connection refused.")
                return
            }

            if (receiveTransfer(secureChannel)) {
                onSessionMessage("Transfer successful! Hash verified.")
            } else {
                onSessionMessage("Transfer failed or hash mismatch.")
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            onSessionMessage("Client session failed: ${exception.message ?: exception::class.simpleName}")
        } finally {
            if (secureChannel != null) {
                closeSafely { closeSecureChannel(secureChannel) }
            } else {
                closeSafely { closeRawConnection(rawConnection) }
            }

            if (!stopping) {
                onListening()
            }
        }
    }

    private suspend fun closeSafely(close: suspend () -> Unit) {
        withContext(NonCancellable) {
            try {
                close()
            } catch (closeError: Exception) {
                onCloseError(closeError)
            }
        }
    }
}
