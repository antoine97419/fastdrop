package com.fastdrop.session

import kotlinx.coroutines.sync.Mutex

/** One active transfer per runtime; other sessions receive BUSY. */
class SessionGate {
    private val mutex = Mutex()
    val isLocked: Boolean get() = mutex.isLocked
    fun tryLock(): Boolean = mutex.tryLock()
    fun unlock() = mutex.unlock()
}
