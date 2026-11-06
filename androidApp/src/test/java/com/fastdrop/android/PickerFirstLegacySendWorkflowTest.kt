package com.fastdrop.android

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class PickerFirstLegacySendWorkflowTest {
    @Test
    fun uriIsSelectedBeforeConnectionStarts() = runTest {
        val events = mutableListOf<String>()
        val workflow = workflow(
            events = events,
            connect = {
                events += "connect"
                TestRawConnection(1)
            }
        )

        workflow.startSelection()
        assertEquals(listOf("state:Selecting file...", "picker"), events)

        workflow.onPickerResult("content://selected")
        assertTrue(events.indexOf("picker") < events.indexOf("connect"))
    }

    @Test
    fun pickerCancellationDoesNotCreateNetworkConnection() = runTest {
        val events = mutableListOf<String>()
        var connected = false
        val workflow = workflow(
            events = events,
            connect = {
                connected = true
                TestRawConnection(1)
            }
        )

        workflow.startSelection()
        val result = workflow.onPickerResult(null)

        assertEquals(LegacySendResult.CANCELLED, result)
        assertFalse(connected)
        assertEquals("state:Ready", events.last())
    }

    @Test
    fun trustedPeerConnectsAfterPickerThenTransfers() = runTest {
        val events = mutableListOf<String>()
        val workflow = workflow(
            events = events,
            authenticate = { raw ->
                events += "trusted:${raw.id}"
                TestSecureConnection(raw.id)
            }
        )

        workflow.startSelection()
        val result = workflow.onPickerResult("content://trusted")

        assertEquals(LegacySendResult.SENT, result)
        assertOrdered(events, "picker", "connect", "trusted:1", "send:content://trusted", "close-secure:1")
        assertTrue(events.contains("state:Sent successfully!"))
    }

    @Test
    fun newPeerRunsSasAfterPickerThenTransfers() = runTest {
        val events = mutableListOf<String>()
        val workflow = workflow(
            events = events,
            authenticate = { raw ->
                events += "sas:${raw.id}"
                events += "sas-confirmed:${raw.id}"
                TestSecureConnection(raw.id)
            }
        )

        workflow.startSelection()
        val result = workflow.onPickerResult("content://new-peer")

        assertEquals(LegacySendResult.SENT, result)
        assertOrdered(events, "picker", "connect", "sas:1", "sas-confirmed:1", "send:content://new-peer")
    }

    @Test
    fun networkErrorAfterSelectionProducesCleanUiState() = runTest {
        val events = mutableListOf<String>()
        val workflow = workflow(
            events = events,
            connect = {
                events += "connect"
                throw IllegalStateException("network unavailable")
            }
        )

        workflow.startSelection()
        val result = workflow.onPickerResult("content://selected")

        assertEquals(LegacySendResult.FAILED, result)
        assertEquals("state:Error: network unavailable", events.last())
        assertFalse(events.any { it.startsWith("send:") })
    }

    @Test
    fun closedSecureChannelIsNeverReusedForNextTransfer() = runTest {
        val events = mutableListOf<String>()
        val secureConnections = mutableListOf<TestSecureConnection>()
        var nextId = 0
        val workflow = workflow(
            events = events,
            connect = { TestRawConnection(++nextId) },
            authenticate = { raw ->
                TestSecureConnection(raw.id).also { secureConnections += it }
            },
            closeSecure = { secure ->
                secure.closed = true
                events += "close-secure:${secure.id}"
            }
        )

        workflow.startSelection()
        assertEquals(LegacySendResult.SENT, workflow.onPickerResult("content://first"))
        workflow.startSelection()
        assertEquals(LegacySendResult.SENT, workflow.onPickerResult("content://second"))

        assertEquals(2, secureConnections.size)
        assertNotSame(secureConnections[0], secureConnections[1])
        assertTrue(secureConnections.all { it.closed })
    }

    private fun workflow(
        events: MutableList<String>,
        connect: suspend () -> TestRawConnection = {
            events += "connect"
            TestRawConnection(1)
        },
        authenticate: suspend (TestRawConnection) -> TestSecureConnection = { raw ->
            events += "authenticate:${raw.id}"
            TestSecureConnection(raw.id)
        },
        closeSecure: suspend (TestSecureConnection) -> Unit = { secure ->
            secure.closed = true
            events += "close-secure:${secure.id}"
        }
    ) = PickerFirstLegacySendWorkflow<String, TestRawConnection, TestSecureConnection>(
        openPicker = { events += "picker" },
        connect = connect,
        authenticate = authenticate,
        send = { secure, uri ->
            check(!secure.closed)
            events += "send:$uri"
            true
        },
        closeSecure = closeSecure,
        closeRaw = { raw -> events += "close-raw:${raw.id}" },
        updateState = { state -> events += "state:$state" }
    )

    private fun assertOrdered(events: List<String>, vararg expected: String) {
        var previousIndex = -1
        expected.forEach { event ->
            val index = events.indexOf(event)
            assertTrue(index > previousIndex, "Expected '$event' after index $previousIndex in $events")
            previousIndex = index
        }
    }

    private data class TestRawConnection(val id: Int)
    private data class TestSecureConnection(val id: Int, var closed: Boolean = false)
}
