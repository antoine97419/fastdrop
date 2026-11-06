package com.fastdrop.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class LegacyGoReceiveWorkflow<Raw : Any, Secure : Any>(
    private val connect: suspend () -> Raw,
    private val authenticate: suspend (Raw) -> Secure,
    private val receive: suspend (Secure) -> Boolean,
    private val closeSecure: suspend (Secure) -> Unit,
    private val closeRaw: suspend (Raw) -> Unit,
    private val updateState: suspend (String) -> Unit,
    private val reportCloseError: (Throwable) -> Unit = { it.printStackTrace() }
) {
    suspend fun run(): Boolean {
        var raw: Raw? = null
        var secure: Secure? = null
        try {
            updateState("Connecting to Windows...")
            raw = connect()
            updateState("Authenticating...")
            secure = authenticate(raw)
            updateState("Waiting for file offer...")
            return receive(secure)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            updateState("Error: " + (failure.message ?: failure::class.simpleName))
            return false
        } finally {
            withContext(NonCancellable) {
                try {
                    if (secure != null) closeSecure(secure) else if (raw != null) closeRaw(raw)
                } catch (closeError: Exception) {
                    reportCloseError(closeError)
                }
            }
        }
    }
}
