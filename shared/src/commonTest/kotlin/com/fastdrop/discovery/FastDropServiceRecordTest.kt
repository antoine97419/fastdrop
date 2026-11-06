package com.fastdrop.discovery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FastDropServiceRecordTest {
    private val id = "AA:".repeat(31) + "AA"

    @Test fun roundTripKeepsPublicIdentityAndCapabilities() {
        val record = FastDropServiceRecord(1, id, "Laptop-Moufitss", setOf("send", "receive"))
        assertEquals(record, FastDropServiceRecord.parse(record.txt(), "other instance"))
    }

    @Test fun rejectsUnconfirmedServiceAndCleansName() {
        assertNull(FastDropServiceRecord.parse(emptyMap(), "HP DeskJet"))
        assertNull(FastDropServiceRecord.parse(mapOf("version" to "2"), "printer"))
        assertEquals("Laptop-Moufitss", FastDropServiceRecord.parse(
            mapOf("version" to "1", "name" to "Laptop-Moufitss ?"), "fallback")?.name)
    }
}
