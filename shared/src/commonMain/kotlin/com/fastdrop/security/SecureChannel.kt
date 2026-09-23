package com.fastdrop.security

import com.fastdrop.core.Peer
import com.fastdrop.transport.Connection
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.XDH
import dev.whyoleg.cryptography.algorithms.HKDF
import dev.whyoleg.cryptography.algorithms.ChaCha20Poly1305
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.BinarySize.Companion.bytes
import kotlin.math.min

enum class SecureChannelState {
    NEW,
    HANDSHAKING,
    WAITING_FOR_SAS_CONFIRMATION,
    ESTABLISHED,
    FAILED,
    CLOSED
}

class HandshakeResult(val sas: String)

class SecureChannel(
    private val rawConnection: Connection
) : Connection {
    override val peer: Peer get() = rawConnection.peer

    var state = SecureChannelState.NEW
        private set

    private var writeKey: ChaCha20Poly1305.Key? = null
    private var readKey: ChaCha20Poly1305.Key? = null

    private var writeNonceCounter = 0L
    private var readNonceCounter = 0L

    private val PROTOCOL_VERSION = "FASTDROP/1".encodeToByteArray()
    
    private val MAX_ENCRYPTED_FRAME_SIZE = 1024 * 1024 * 2

    private var internalReadBuffer = ByteArray(0)
    private var internalReadOffset = 0

    private fun compareByteArrays(a: ByteArray, b: ByteArray): Int {
        val len = min(a.size, b.size)
        for (i in 0 until len) {
            val aByte = a[i].toInt() and 0xFF
            val bByte = b[i].toInt() and 0xFF
            if (aByte != bByte) return aByte.compareTo(bByte)
        }
        return a.size.compareTo(b.size)
    }

    private fun buildNonce(counter: Long): ByteArray {
        val nonce = ByteArray(12)
        var c = counter
        for (i in 11 downTo 4) {
            nonce[i] = (c and 0xFF).toByte()
            c = c shr 8
        }
        return nonce
    }

    suspend fun handshake(provider: CryptographyProvider = CryptographyProvider.Default): HandshakeResult {
        state = SecureChannelState.HANDSHAKING
        try {
            val xdh = provider.get(XDH)
            val keyPair = xdh.keyPairGenerator(XDH.Curve.X25519).generateKey()
            val myPubKeyBytes = keyPair.publicKey.encodeToByteArray(XDH.PublicKey.Format.RAW) 
            
            val outBuf = ByteArray(PROTOCOL_VERSION.size + myPubKeyBytes.size)
            PROTOCOL_VERSION.copyInto(outBuf, 0)
            myPubKeyBytes.copyInto(outBuf, PROTOCOL_VERSION.size)
            rawConnection.write(outBuf, 0, outBuf.size)
            
            val inBuf = ByteArray(outBuf.size)
            readExactlyFromRaw(inBuf, outBuf.size)
            
            val peerVersion = inBuf.copyOfRange(0, PROTOCOL_VERSION.size)
            if (!peerVersion.contentEquals(PROTOCOL_VERSION)) throw IllegalStateException("UNSUPPORTED_VERSION")
            
            val peerPubKeyBytes = inBuf.copyOfRange(PROTOCOL_VERSION.size, inBuf.size)
            val peerPubKey = xdh.publicKeyDecoder(XDH.Curve.X25519).decodeFromByteArray(XDH.PublicKey.Format.RAW, peerPubKeyBytes)
            val sharedSecret = keyPair.privateKey.sharedSecretGenerator().generateSharedSecret(peerPubKey)
            
            val isMyKeySmaller = compareByteArrays(myPubKeyBytes, peerPubKeyBytes) < 0
            val keyA = if (isMyKeySmaller) myPubKeyBytes else peerPubKeyBytes
            val keyB = if (isMyKeySmaller) peerPubKeyBytes else myPubKeyBytes
            
            val transcriptData = PROTOCOL_VERSION + keyA + keyB
            val transcriptHash = provider.get(SHA256).hasher().hash(transcriptData)
            
            val hkdf = provider.get(HKDF)
            
            val hkdfDeriveAtoB = hkdf.secretDerivation(SHA256, outputSize = 32.bytes, salt = transcriptHash, info = "fastdrop-v1/a-to-b".encodeToByteArray())
            val keyAToBBytes = hkdfDeriveAtoB.deriveSecretToByteArray(sharedSecret)
            
            val hkdfDeriveBtoA = hkdf.secretDerivation(SHA256, outputSize = 32.bytes, salt = transcriptHash, info = "fastdrop-v1/b-to-a".encodeToByteArray())
            val keyBToABytes = hkdfDeriveBtoA.deriveSecretToByteArray(sharedSecret)
            
            val hkdfDeriveSas = hkdf.secretDerivation(SHA256, outputSize = 4.bytes, salt = transcriptHash, info = "fastdrop-v1/sas".encodeToByteArray())
            val sasBytes = hkdfDeriveSas.deriveSecretToByteArray(sharedSecret)
            
            val myWriteKeyBytes = if (isMyKeySmaller) keyAToBBytes else keyBToABytes
            val myReadKeyBytes = if (isMyKeySmaller) keyBToABytes else keyAToBBytes
            
            val chacha = provider.get(ChaCha20Poly1305)
            writeKey = chacha.keyDecoder().decodeFromByteArray(ChaCha20Poly1305.Key.Format.RAW, myWriteKeyBytes)
            readKey = chacha.keyDecoder().decodeFromByteArray(ChaCha20Poly1305.Key.Format.RAW, myReadKeyBytes)
            
            val sasNumber = ((sasBytes[0].toInt() and 0xFF) shl 24) or
                            ((sasBytes[1].toInt() and 0xFF) shl 16) or
                            ((sasBytes[2].toInt() and 0xFF) shl 8) or
                            (sasBytes[3].toInt() and 0xFF)
            
            val sasCode = (sasNumber and 0x7FFFFFFF) % 1_000_000
            val sasString = sasCode.toString().padStart(6, '0')
            
            state = SecureChannelState.WAITING_FOR_SAS_CONFIRMATION
            return HandshakeResult(sasString)
            
        } catch (e: Exception) {
            state = SecureChannelState.FAILED
            rawConnection.close()
            throw e
        }
    }

    fun confirmPeer() {
        if (state == SecureChannelState.WAITING_FOR_SAS_CONFIRMATION) {
            state = SecureChannelState.ESTABLISHED
        }
    }

    private suspend fun readExactlyFromRaw(buffer: ByteArray, length: Int) {
        var offset = 0
        while (offset < length) {
            val chunk = ByteArray(length - offset)
            val read = rawConnection.read(chunk)
            if (read <= 0) throw IllegalStateException("EOF")
            chunk.copyInto(buffer, offset, 0, read)
            offset += read
        }
    }

    @OptIn(DelicateCryptographyApi::class)
    override suspend fun read(buffer: ByteArray): Int {
        if (state != SecureChannelState.ESTABLISHED) throw IllegalStateException("Channel not established")
        
        if (internalReadOffset < internalReadBuffer.size) {
            val available = internalReadBuffer.size - internalReadOffset
            val toCopy = min(available, buffer.size)
            internalReadBuffer.copyInto(buffer, 0, internalReadOffset, internalReadOffset + toCopy)
            internalReadOffset += toCopy
            return toCopy
        }

        try {
            val lenBytes = ByteArray(4)
            readExactlyFromRaw(lenBytes, 4)
            val encryptedLen = ((lenBytes[0].toInt() and 0xFF) shl 24) or
                               ((lenBytes[1].toInt() and 0xFF) shl 16) or
                               ((lenBytes[2].toInt() and 0xFF) shl 8) or
                               (lenBytes[3].toInt() and 0xFF)
            
            if (encryptedLen <= 0 || encryptedLen > MAX_ENCRYPTED_FRAME_SIZE) {
                throw IllegalArgumentException("Invalid encrypted frame size: $encryptedLen")
            }

            val encryptedBuffer = ByteArray(encryptedLen)
            readExactlyFromRaw(encryptedBuffer, encryptedLen)
            
            if (readNonceCounter == Long.MAX_VALUE) throw IllegalStateException("Nonce overflow")
            val nonce = buildNonce(readNonceCounter++)
            
            internalReadBuffer = readKey!!.cipher().decryptWithIv(nonce, encryptedBuffer, lenBytes)
            internalReadOffset = 0

            val toCopy = min(internalReadBuffer.size, buffer.size)
            internalReadBuffer.copyInto(buffer, 0, 0, toCopy)
            internalReadOffset = toCopy
            return toCopy
        } catch (e: Exception) {
            state = SecureChannelState.FAILED
            rawConnection.close()
            throw e
        }
    }

    @OptIn(DelicateCryptographyApi::class)
    override suspend fun write(data: ByteArray, offset: Int, length: Int) {
        if (state != SecureChannelState.ESTABLISHED) throw IllegalStateException("Channel not established")
        
        try {
            if (writeNonceCounter == Long.MAX_VALUE) throw IllegalStateException("Nonce overflow")
            val nonce = buildNonce(writeNonceCounter++)
            
            val plaintext = data.copyOfRange(offset, offset + length)
            val cipher = writeKey!!.cipher()
            
            val expectedCipherLen = plaintext.size + 16
            
            val lenBytes = ByteArray(4)
            lenBytes[0] = (expectedCipherLen shr 24).toByte()
            lenBytes[1] = (expectedCipherLen shr 16).toByte()
            lenBytes[2] = (expectedCipherLen shr 8).toByte()
            lenBytes[3] = expectedCipherLen.toByte()

            val ciphertext = cipher.encryptWithIv(nonce, plaintext, lenBytes)
            
            val outBuf = ByteArray(4 + ciphertext.size)
            lenBytes.copyInto(outBuf, 0)
            ciphertext.copyInto(outBuf, 4)
            
            rawConnection.write(outBuf, 0, outBuf.size)
        } catch (e: Exception) {
            state = SecureChannelState.FAILED
            rawConnection.close()
            throw e
        }
    }

    override suspend fun close() {
        state = SecureChannelState.CLOSED
        rawConnection.close()
    }
}
