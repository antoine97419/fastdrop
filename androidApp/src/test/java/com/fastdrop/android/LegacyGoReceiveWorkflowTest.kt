package com.fastdrop.android

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LegacyGoReceiveWorkflowTest {
    @Test
    fun androidConnectsAndWaitsForOfferBeforeReceiving() = runTest {
        val offered = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val workflow = LegacyGoReceiveWorkflow(
            connect = { events += "connect"; "raw" },
            authenticate = { events += "authenticate"; "secure" },
            receive = { events += "waiting"; offered.await(); events += "receive"; true },
            closeSecure = { events += "close secure" },
            closeRaw = { events += "close raw" },
            updateState = { events += it }
        )
        val result = async { workflow.run() }
        runCurrent()
        assertFalse(result.isCompleted)
        assertTrue(events.contains("Waiting for file offer..."))
        offered.complete(Unit)
        assertTrue(result.await())
        assertEquals("close secure", events.last())
        assertEquals(1, events.count { it == "connect" })
    }

    @Test
    fun failedAuthenticationClosesRawConnection() = runTest {
        val events = mutableListOf<String>()
        val workflow = LegacyGoReceiveWorkflow(
            connect = { "raw" },
            authenticate = { error("SAS rejected") },
            receive = { _: String -> true },
            closeSecure = { _: String -> events += "secure" },
            closeRaw = { _: String -> events += "raw" },
            updateState = { events += it }
        )
        assertFalse(workflow.run())
        assertEquals("raw", events.last())
    }

    @Test
    fun successiveSessionsCreateAndCloseDistinctSecureChannels() = runTest {
        var sequence = 0
        val channels = mutableListOf<Int>()
        val workflow = LegacyGoReceiveWorkflow(
            connect = { ++sequence },
            authenticate = { raw: Int -> raw },
            receive = { secure: Int -> channels += secure; true },
            closeSecure = { channels += -it },
            closeRaw = { _: Int -> error("unused") },
            updateState = {}
        )
        assertTrue(workflow.run())
        assertTrue(workflow.run())
        assertEquals(listOf(1, -1, 2, -2), channels)
    }
}
