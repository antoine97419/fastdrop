package com.fastdrop.session

import com.fastdrop.core.Peer
import com.fastdrop.core.TransportType
import com.fastdrop.transport.Connection
import com.fastdrop.transport.readExactly
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SessionProtocolTest {
    private class Pipe(private val incoming: Channel<Byte>, private val outgoing: Channel<Byte>) : Connection {
        override val peer = Peer("peer", "Peer", TransportType.LAN, "127.0.0.1")
        override suspend fun read(buffer: ByteArray): Int {
            val first = incoming.receiveCatching().getOrNull() ?: return -1
            buffer[0] = first
            var count = 1
            while (count < buffer.size) {
                val next = incoming.tryReceive().getOrNull() ?: break
                buffer[count++] = next
            }
            return count
        }
        override suspend fun write(data: ByteArray, offset: Int, length: Int) {
            for (index in offset until offset + length) outgoing.send(data[index])
        }
        override suspend fun close() { incoming.close(); outgoing.close() }
    }
    private fun pair(): Pair<Connection, Connection> {
        val a = Channel<Byte>(Channel.UNLIMITED)
        val b = Channel<Byte>(Channel.UNLIMITED)
        return Pipe(a, b) to Pipe(b, a)
    }

    @Test fun helloSendIntentAndReadyLeaveChannelForFileProtocol() = runTest {
        val (left, right) = pair()
        val sender = SessionProtocol(left)
        val receiver = SessionProtocol(right)
        val request = async { sender.requestSend() }
        assertTrue(receiver.acceptSendIntent { true })
        assertTrue(request.await())
        left.write(byteArrayOf(42))
        assertEquals(42, right.readExactly(1)!![0].toInt())
    }

    @Test fun busyRejectsConcurrentIntent() = runTest {
        val (left, right) = pair()
        val request = async { SessionProtocol(left).requestSend() }
        assertFalse(SessionProtocol(right).acceptSendIntent { false })
        assertFalse(request.await())
    }

    @Test fun unsupportedVersionGetsCancel() = runTest {
        val (left, right) = pair()
        val sender = SessionProtocol(left)
        val receiver = SessionProtocol(right)
        sender.write(SessionMessage.Hello(99))
        assertFailsWith<SessionProtocolException> { receiver.acceptSendIntent { true } }
        assertEquals(SessionMessage.Cancel(SessionCancelReason.UNSUPPORTED_VERSION), sender.read())
    }

    @Test fun cancelAndInvalidIntent() = runTest {
        val (left, right) = pair()
        val sender = SessionProtocol(left)
        val receiver = SessionProtocol(right)
        sender.write(SessionMessage.Hello(1))
        val accept = async { receiver.acceptSendIntent { true } }
        assertEquals(SessionMessage.Hello(1), sender.read())
        sender.write(SessionMessage.Cancel(SessionCancelReason.USER_CANCELLED))
        assertFalse(accept.await())
    }

    @Test fun androidPreparedReceiverAcceptsWindowsSendWithoutReceiveAction() = runTest {
        val (android, windows) = pair()
        val receiver = async { SessionProtocol(android).offerReceive { true } }
        val ready = async {
            SessionProtocol(windows).acceptIncoming({ true }) {
                assertTrue(SessionProtocol(windows).beginPreparedSend())
            }
        }
        assertEquals(IncomingSessionResult.PREPARED_DONE, ready.await())
        assertTrue(receiver.await())
        windows.write(byteArrayOf(17))
        assertEquals(17, android.readExactly(1)!![0].toInt())
    }

    @Test fun preparedReceiverReturnsBusyForCollision() = runTest {
        val (android, windows) = pair()
        val receiver = async { SessionProtocol(android).offerReceive { false } }
        val ready = async {
            SessionProtocol(windows).acceptIncoming({ true }) {
                assertFalse(SessionProtocol(windows).beginPreparedSend())
            }
        }
        assertEquals(IncomingSessionResult.PREPARED_DONE, ready.await())
        assertFalse(receiver.await())
    }

    @Test fun malformedMessageIsRejected() = runTest {
        val (left, right) = pair()
        left.write(byteArrayOf(0, 0, 0, 2, 123, 125))
        assertFailsWith<SessionProtocolException> { SessionProtocol(right).read() }
    }

    @Test fun invalidIntentGetsTypedCancel() = runTest {
        val (left, right) = pair()
        val sender = SessionProtocol(left)
        val receiver = SessionProtocol(right)
        sender.write(SessionMessage.Hello(1))
        val accept = async {
            assertFailsWith<SessionProtocolException> { receiver.acceptSendIntent { true } }
        }
        assertEquals(SessionMessage.Hello(1), sender.read())
        sender.write(SessionMessage.Ready)
        assertEquals(SessionMessage.Cancel(SessionCancelReason.INVALID_INTENT), sender.read())
        accept.await()
    }
}
