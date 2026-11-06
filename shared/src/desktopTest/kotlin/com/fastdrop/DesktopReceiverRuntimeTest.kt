package com.fastdrop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopReceiverRuntimeTest {
    @Test
    fun desktopShutdownStopsBridgeAfterReceiverAndDiscovery() = runTest {
        val fixture = RuntimeFixture()
        val job = launch { fixture.runtime.run() }
        runCurrent()

        fixture.runtime.stop()
        runCurrent()

        assertFalse(job.isActive)
        assertEquals(listOf("receiver", "discovery", "bridge"), fixture.stopOrder)
        assertEquals(1, fixture.bridge.stopCount)
        assertFalse(fixture.bridge.isRunning())
    }

    @Test
    fun successiveTransfersDoNotRestartBridge() = runTest {
        val fixture = RuntimeFixture()
        val job = launch { fixture.runtime.run() }
        runCurrent()

        fixture.sessions.send(Unit)
        fixture.sessions.send(Unit)
        runCurrent()

        assertEquals(2, fixture.processedSessions)
        assertEquals(1, fixture.bridge.startCount)
        assertTrue(fixture.bridge.isRunning())
        fixture.runtime.stop()
        runCurrent()
        assertFalse(job.isActive)
    }

    @Test
    fun clientSessionErrorDoesNotStopBridge() = runTest {
        val fixture = RuntimeFixture(failFirstSession = true)
        val job = launch { fixture.runtime.run() }
        runCurrent()

        fixture.sessions.send(Unit)
        fixture.sessions.send(Unit)
        runCurrent()

        assertEquals(2, fixture.processedSessions)
        assertEquals(1, fixture.bridge.startCount)
        assertEquals(0, fixture.bridge.stopCount)
        assertTrue(fixture.bridge.isRunning())
        fixture.runtime.stop()
        runCurrent()
        assertFalse(job.isActive)
    }

    @Test
    fun unexpectedBridgeExitStopsReceiver() = runTest {
        val fixture = RuntimeFixture()
        val job = launch { fixture.runtime.run() }
        runCurrent()

        fixture.bridge.fail("bridge crashed")
        runCurrent()

        assertFalse(job.isActive)
        assertTrue(fixture.stopOrder.contains("receiver"))
        fixture.runtime.stop()
        runCurrent()
    }

    private class RuntimeFixture(failFirstSession: Boolean = false) {
        val stopOrder = mutableListOf<String>()
        val bridge = FakeBridgeLifecycle { stopOrder += "bridge" }
        val sessions = Channel<Unit>(Channel.UNLIMITED)
        val receiverStopped = CompletableDeferred<Unit>()
        var processedSessions = 0

        val runtime = DesktopReceiverRuntime(
            bridge = bridge,
            runReceiver = {
                while (!receiverStopped.isCompleted) {
                    kotlinx.coroutines.selects.select<Unit> {
                        sessions.onReceive {
                            processedSessions++
                            if (failFirstSession && processedSessions == 1) {
                                runCatching { error("client reset") }
                            }
                        }
                        receiverStopped.onAwait { }
                    }
                }
            },
            stopReceiver = {
                stopOrder += "receiver"
                receiverStopped.complete(Unit)
            },
            stopDiscovery = { stopOrder += "discovery" },
            log = {}
        )
    }

    private class FakeBridgeLifecycle(private val onStop: () -> Unit) : LegacyGoBridgeLifecycle {
        var startCount = 0
        var stopCount = 0
        private var running = false
        private val failure = CompletableDeferred<BridgeFailure>()

        override suspend fun start() {
            startCount++
            running = true
        }

        override suspend fun stop() {
            stopCount++
            running = false
            onStop()
        }

        override fun isRunning(): Boolean = running
        override suspend fun awaitUnexpectedExit(): BridgeFailure = failure.await()

        fun fail(message: String) {
            running = false
            failure.complete(BridgeFailure(message))
        }
    }
}
