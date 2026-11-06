package com.fastdrop

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PersistentReceiverServerTest {
    @Test
    fun senderRoleWaitsForAndroidConnection() = runTest {
        val fixture = ReceiverFixture()
        val job = launch { fixture.server.run() }
        runCurrent()

        assertTrue(job.isActive)
        assertTrue(fixture.receivedIds.isEmpty())
        fixture.connections.send(TestRawConnection(1))
        runCurrent()

        assertEquals(listOf(1), fixture.receivedIds)
        fixture.server.stop()
    }

    @Test
    fun successfulTransferLeavesReceiverActive() = runTest {
        val fixture = ReceiverFixture()
        val job = launch { fixture.server.run() }
        runCurrent()

        fixture.connections.send(TestRawConnection(1))
        runCurrent()

        assertTrue(job.isActive)
        assertEquals(2, fixture.listeningCount)
        assertTrue(fixture.messages.contains("Transfer successful! Hash verified."))
        fixture.server.stop()
    }

    @Test
    fun acceptsSecondConnectionAfterFirstTransfer() = runTest {
        val fixture = ReceiverFixture()
        val job = launch { fixture.server.run() }
        runCurrent()

        fixture.connections.send(TestRawConnection(1))
        fixture.connections.send(TestRawConnection(2))
        runCurrent()

        assertEquals(listOf(1, 2), fixture.receivedIds)
        assertTrue(job.isActive)
        fixture.server.stop()
    }

    @Test
    fun clientDisconnectBeforeOfferDoesNotStopReceiver() = runTest {
        val fixture = ReceiverFixture(
            receive = { secure ->
                if (secure.rawId == 1) throw IllegalStateException("EOF before FILE_OFFER")
                true
            }
        )
        val job = launch { fixture.server.run() }
        runCurrent()

        fixture.connections.send(TestRawConnection(1))
        fixture.connections.send(TestRawConnection(2))
        runCurrent()

        assertTrue(fixture.messages.any { it.contains("EOF before FILE_OFFER") })
        assertEquals(listOf(1, 2), fixture.receivedIds)
        assertTrue(job.isActive)
        fixture.server.stop()
    }

    @Test
    fun failedTransferReturnsToListening() = runTest {
        val fixture = ReceiverFixture(receive = { false })
        val job = launch { fixture.server.run() }
        runCurrent()

        fixture.connections.send(TestRawConnection(1))
        runCurrent()

        assertTrue(job.isActive)
        assertEquals(2, fixture.listeningCount)
        assertTrue(fixture.messages.contains("Transfer failed or hash mismatch."))
        fixture.server.stop()
    }

    @Test
    fun rejectedSasReturnsToListeningWithoutReceiving() = runTest {
        val fixture = ReceiverFixture(authenticate = { false })
        val job = launch { fixture.server.run() }
        runCurrent()

        fixture.connections.send(TestRawConnection(1))
        runCurrent()

        assertTrue(job.isActive)
        assertTrue(fixture.receivedIds.isEmpty())
        assertEquals(2, fixture.listeningCount)
        assertTrue(fixture.messages.contains("Connection refused."))
        fixture.server.stop()
    }

    @Test
    fun successiveSessionsUseDifferentSecureChannels() = runTest {
        val fixture = ReceiverFixture()
        val job = launch { fixture.server.run() }
        runCurrent()

        fixture.connections.send(TestRawConnection(1))
        fixture.connections.send(TestRawConnection(2))
        runCurrent()

        assertEquals(2, fixture.secureChannels.size)
        assertNotSame(fixture.secureChannels[0], fixture.secureChannels[1])
        assertTrue(fixture.secureChannels.all { it.closed })
        fixture.server.stop()
    }

    @Test
    fun explicitStopClosesListenerAndActiveSessionCleanly() = runTest {
        val fixture = ReceiverFixture(receive = { awaitCancellation() })
        val job = launch { fixture.server.run() }
        runCurrent()

        fixture.connections.send(TestRawConnection(1))
        runCurrent()
        fixture.server.stop()
        runCurrent()

        assertEquals(1, fixture.stopCount)
        assertFalse(job.isActive)
        assertTrue(fixture.secureChannels.single().closed)
    }

    private class ReceiverFixture(
        private val authenticate: suspend (TestSecureConnection) -> Boolean = { true },
        private val receive: suspend (TestSecureConnection) -> Boolean = { true }
    ) {
        val connections = Channel<TestRawConnection>(Channel.UNLIMITED)
        val secureChannels = mutableListOf<TestSecureConnection>()
        val receivedIds = mutableListOf<Int>()
        val messages = mutableListOf<String>()
        var listeningCount = 0
        var stopCount = 0

        val server = PersistentReceiverServer(
            startHosting = { connections.receiveAsFlow() },
            createSecureChannel = { raw ->
                TestSecureConnection(raw.id).also { secureChannels += it }
            },
            authenticate = authenticate,
            receiveTransfer = { secure ->
                receivedIds += secure.rawId
                receive(secure)
            },
            closeSecureChannel = { secure -> secure.closed = true },
            closeRawConnection = { raw -> raw.closed = true },
            stopHosting = {
                stopCount++
                connections.close()
            },
            onListening = { listeningCount++ },
            onSessionMessage = { messages += it }
        )
    }

    private data class TestRawConnection(val id: Int, var closed: Boolean = false)
    private data class TestSecureConnection(val rawId: Int, var closed: Boolean = false)
}
