package com.fastdrop

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class DesktopReceiverRuntime(
    private val bridge: LegacyGoBridgeLifecycle,
    private val runReceiver: suspend () -> Unit,
    private val stopReceiver: suspend () -> Unit,
    private val stopDiscovery: suspend () -> Unit,
    private val log: (String) -> Unit = ::println
) {
    private val stopMutex = Mutex()

    @Volatile
    private var receiverStarted = false

    @Volatile
    private var stopped = false

    suspend fun run() {
        log("Starting Windows Wi-Fi Direct bridge...")
        bridge.start()
        log("Bridge ready: DIRECT-FD-FastDropPC")

        receiverStarted = true
        try {
            coroutineScope {
                val bridgeWatcher = launch {
                    val failure = bridge.awaitUnexpectedExit()
                    log("Wi-Fi Direct bridge stopped: ${failure.message}")
                    stopReceiver()
                }
                try {
                    runReceiver()
                } finally {
                    bridgeWatcher.cancelAndJoin()
                }
            }
        } finally {
            receiverStarted = false
        }
    }

    suspend fun stop() {
        stopMutex.withLock {
            if (stopped) return@withLock
            stopped = true

            withContext(NonCancellable) {
                if (receiverStarted) runCatching { stopReceiver() }
                    .onFailure { log("Failed to stop receiver: ${it.message}") }
                runCatching { stopDiscovery() }
                    .onFailure { log("Failed to stop discovery: ${it.message}") }
                runCatching { bridge.stop() }
                    .onFailure { log("Failed to stop Wi-Fi Direct bridge: ${it.message}") }
                Unit
            }
        }
    }
}
