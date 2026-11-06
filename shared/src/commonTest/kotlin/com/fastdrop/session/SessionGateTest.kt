package com.fastdrop.session

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionGateTest {
    @Test fun simultaneousRequestsAllowOnlyOneTransfer() {
        val gate = SessionGate()
        assertTrue(gate.tryLock())
        assertTrue(gate.isLocked)
        assertFalse(gate.tryLock()) // the opposite direction must answer BUSY
        gate.unlock()
        assertTrue(gate.tryLock()) // a later transfer can start
        gate.unlock()
        assertFalse(gate.isLocked)
    }
}
