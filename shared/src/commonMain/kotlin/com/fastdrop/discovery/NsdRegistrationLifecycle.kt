package com.fastdrop.discovery

/** Serializes an asynchronous DNS-SD registration across Activity recreation. */
class NsdRegistrationLifecycle(
    private val register: () -> Unit,
    private val unregister: () -> Unit,
    private val browse: () -> Unit,
    private val stopBrowsing: () -> Unit,
    private val onError: (Throwable) -> Unit = {}
) {
    enum class State { STOPPED, STARTING, STARTED, STOPPING }

    var state = State.STOPPED
        private set
    private var restartAfterStop = false

    @Synchronized fun start() {
        when (state) {
            State.STARTING, State.STARTED -> return
            State.STOPPING -> { restartAfterStop = true; return }
            State.STOPPED -> Unit
        }
        state = State.STARTING
        try {
            register()
        } catch (failure: Exception) {
            state = State.STOPPED
            throw failure
        }
        if (state != State.STARTING && state != State.STARTED) return
        try {
            browse()
        } catch (failure: Exception) {
            runCatching { stopBrowsing() }
            state = State.STOPPING
            unregister()
            throw failure
        }
    }

    @Synchronized fun stop() {
        restartAfterStop = false
        if (state == State.STOPPED || state == State.STOPPING) return
        state = State.STOPPING
        runCatching { stopBrowsing() }
        unregister()
    }

    @Synchronized fun registered() {
        if (state == State.STARTING) state = State.STARTED
    }

    @Synchronized fun registrationFailed() {
        if (state == State.STARTING || state == State.STOPPING) {
            runCatching { stopBrowsing() }
            state = State.STOPPED
            if (restartAfterStop) {
                restartAfterStop = false
                runCatching { start() }.onFailure(onError)
            }
        }
    }

    @Synchronized fun unregistered() {
        if (state != State.STOPPING) return
        state = State.STOPPED
        if (restartAfterStop) {
            restartAfterStop = false
            runCatching { start() }.onFailure(onError)
        }
    }
}
