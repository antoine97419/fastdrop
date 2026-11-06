package com.fastdrop.session

import com.fastdrop.transport.Connection
import com.fastdrop.transport.readExactly
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.withTimeout

@Serializable
enum class SessionCancelReason { UNSUPPORTED_VERSION, INVALID_INTENT, USER_CANCELLED }

@Serializable
sealed interface SessionMessage {
    @Serializable @SerialName("HELLO") data class Hello(val version: Int) : SessionMessage
    @Serializable @SerialName("RECEIVE_READY") data object ReceiveReady : SessionMessage
    @Serializable @SerialName("SEND_INTENT") data object SendIntent : SessionMessage
    @Serializable @SerialName("READY") data object Ready : SessionMessage
    @Serializable @SerialName("BUSY") data object Busy : SessionMessage
    @Serializable @SerialName("CANCEL") data class Cancel(val reason: SessionCancelReason) : SessionMessage
}

class SessionProtocolException(message: String) : Exception(message)

enum class IncomingSessionResult { SEND_READY, PREPARED_DONE, BUSY }

/** One negotiation per fresh SecureChannel; TransferManager owns all subsequent bytes. */
class SessionProtocol(private val channel: Connection) {
    companion object { const val VERSION = 1; private const val MAX_MESSAGE = 4096 }
    private val json = Json { classDiscriminator = "type" }

    suspend fun write(message: SessionMessage) {
        val body = json.encodeToString<SessionMessage>(message).encodeToByteArray()
        require(body.size in 1..MAX_MESSAGE)
        val size = body.size
        val header = byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(),
            (size ushr 8).toByte(), size.toByte())
        channel.write(header + body)
    }

    suspend fun read(): SessionMessage {
        val header = channel.readExactly(4) ?: throw SessionProtocolException("Session closed")
        val size = ((header[0].toInt() and 255) shl 24) or
            ((header[1].toInt() and 255) shl 16) or
            ((header[2].toInt() and 255) shl 8) or (header[3].toInt() and 255)
        if (size !in 1..MAX_MESSAGE) throw SessionProtocolException("Invalid session message size")
        val body = channel.readExactly(size) ?: throw SessionProtocolException("Session closed")
        return try { json.decodeFromString<SessionMessage>(body.decodeToString()) }
        catch (_: Exception) { throw SessionProtocolException("Invalid session message") }
    }

    suspend fun offerReceive(available: () -> Boolean): Boolean {
        write(SessionMessage.Hello(VERSION))
        val hello = read()
        if (hello !is SessionMessage.Hello || hello.version != VERSION)
            throw SessionProtocolException("Incompatible session version")
        write(SessionMessage.ReceiveReady)
        return when (read()) {
            SessionMessage.SendIntent -> {
                val ready = available()
                write(if (ready) SessionMessage.Ready else SessionMessage.Busy)
                ready
            }
            SessionMessage.Busy, is SessionMessage.Cancel -> false
            else -> throw SessionProtocolException("Unexpected session response")
        }
    }

    suspend fun beginPreparedSend(): Boolean {
        write(SessionMessage.SendIntent)
        return when (val response = read()) {
            SessionMessage.Ready -> true
            SessionMessage.Busy -> false
            is SessionMessage.Cancel -> throw SessionProtocolException(response.reason.name)
            else -> throw SessionProtocolException("Unexpected session response")
        }
    }

    suspend fun acceptIncoming(
        available: () -> Boolean,
        onPrepared: suspend () -> Unit
    ): IncomingSessionResult {
        val hello = withTimeout(10_000) { read() }
        if (hello !is SessionMessage.Hello || hello.version != VERSION) {
            write(SessionMessage.Cancel(SessionCancelReason.UNSUPPORTED_VERSION))
            throw SessionProtocolException("Incompatible session version")
        }
        write(SessionMessage.Hello(VERSION))
        return when (withTimeout(10_000) { read() }) {
            SessionMessage.SendIntent -> {
                val ready = available()
                write(if (ready) SessionMessage.Ready else SessionMessage.Busy)
                if (ready) IncomingSessionResult.SEND_READY else IncomingSessionResult.BUSY
            }
            SessionMessage.ReceiveReady -> {
                onPrepared()
                IncomingSessionResult.PREPARED_DONE
            }
            is SessionMessage.Cancel -> IncomingSessionResult.BUSY
            else -> {
                write(SessionMessage.Cancel(SessionCancelReason.INVALID_INTENT))
                throw SessionProtocolException("Invalid session intent")
            }
        }
    }

    suspend fun requestSend(): Boolean {
        write(SessionMessage.Hello(VERSION))
        val hello = read()
        if (hello !is SessionMessage.Hello || hello.version != VERSION)
            throw SessionProtocolException("Incompatible session version")
        write(SessionMessage.SendIntent)
        return when (val response = read()) {
            SessionMessage.Ready -> true
            SessionMessage.Busy -> false
            is SessionMessage.Cancel -> throw SessionProtocolException(response.reason.name)
            else -> throw SessionProtocolException("Unexpected session response")
        }
    }

    suspend fun acceptSendIntent(available: () -> Boolean): Boolean {
        val hello = read()
        if (hello !is SessionMessage.Hello || hello.version != VERSION) {
            write(SessionMessage.Cancel(SessionCancelReason.UNSUPPORTED_VERSION))
            throw SessionProtocolException("Incompatible session version")
        }
        write(SessionMessage.Hello(VERSION))
        return when (read()) {
            SessionMessage.SendIntent -> {
                val ready = available()
                write(if (ready) SessionMessage.Ready else SessionMessage.Busy)
                ready
            }
            is SessionMessage.Cancel -> false
            else -> {
                write(SessionMessage.Cancel(SessionCancelReason.INVALID_INTENT))
                throw SessionProtocolException("Invalid session intent")
            }
        }
    }
}
