package com.fastdrop.discovery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NsdRegistrationLifecycleTest {
    private class FakeNsd {
        var registrations = 0
        var unregistrations = 0
        var browsers = 0
        var stoppedBrowsers = 0
        var active = 0
        var failRegister = false
        val lifecycle = NsdRegistrationLifecycle(
            register = {
                if (failRegister) error("NSD unavailable")
                registrations++
                active++
            },
            unregister = { unregistrations++ },
            browse = { browsers++ },
            stopBrowsing = { stoppedBrowsers++ }
        )
        fun unregistered() { active--; lifecycle.unregistered() }
    }

    @Test fun startAndStopAreIdempotent() {
        val fake = FakeNsd()
        fake.lifecycle.start()
        fake.lifecycle.start()
        fake.lifecycle.registered()
        assertEquals(1, fake.registrations)
        assertEquals(1, fake.browsers)
        fake.lifecycle.stop()
        fake.lifecycle.stop()
        assertEquals(1, fake.unregistrations)
        fake.unregistered()
        assertEquals(NsdRegistrationLifecycle.State.STOPPED, fake.lifecycle.state)
    }

    @Test fun recreationWaitsForUnregisterCallback() {
        val fake = FakeNsd()
        fake.lifecycle.start()
        fake.lifecycle.registered()
        fake.lifecycle.stop()
        fake.lifecycle.start() // new Activity while Android has not completed unregister
        assertEquals(1, fake.registrations)
        assertEquals(1, fake.active)
        fake.unregistered()
        assertEquals(2, fake.registrations)
        assertEquals(1, fake.active)
        fake.lifecycle.registered()
        fake.lifecycle.stop()
        fake.unregistered()
        assertEquals(0, fake.active)
    }

    @Test fun registrationErrorAllowsLaterRetry() {
        val fake = FakeNsd()
        fake.failRegister = true
        assertFailsWith<IllegalStateException> { fake.lifecycle.start() }
        assertEquals(NsdRegistrationLifecycle.State.STOPPED, fake.lifecycle.state)
        assertEquals(0, fake.active)
        fake.failRegister = false
        fake.lifecycle.start()
        assertEquals(1, fake.registrations)
    }

    @Test fun failedRegistrationDuringRecreationDoesNotStickStopping() {
        val fake = FakeNsd()
        fake.lifecycle.start()
        fake.lifecycle.stop()
        fake.lifecycle.start()
        fake.lifecycle.registrationFailed()
        assertEquals(2, fake.registrations)
        assertEquals(NsdRegistrationLifecycle.State.STARTING, fake.lifecycle.state)
    }
}
