package com.fastdrop.security

import com.fastdrop.core.Peer
import com.fastdrop.transport.Connection
import dev.whyoleg.cryptography.BinarySize.Companion.bytes
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.ChaCha20Poly1305
import dev.whyoleg.cryptography.algorithms.HKDF
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.algorithms.XDH
import dev.whyoleg.cryptography.algorithms.EdDSA
import dev.whyoleg.cryptography.operations.IvAuthenticatedCipher
import kotlin.math.min

private val PROTOCOL_VERSION = "FASTDROP/1".encodeToByteArray()
private const val MAX_ENCRYPTED_FRAME_SIZE = 10 * 1024 * 1024

enum class SecureChannelState {
    NEW,
    HANDSHAKING,
    WAITING_FOR_SAS_CONFIRMATION,
    ESTABLISHED,
    CLOSED,
    FAILED
}

class SecureChannel(
    private val rawConnection: Connection,
    private val identityStore: IdentityStore,
    private val trustedPeerStore: TrustedPeerStore,
    private val deviceInfoProvider: DeviceInfoProvider,
    private val provider: CryptographyProvider = CryptographyProvider.Default
) : Connection {
    override val peer: Peer get() = rawConnection.peer

    var state: SecureChannelState = SecureChannelState.NEW
        private set

    private var writeKey: ChaCha20Poly1305.Key? = null
    private var readKey: ChaCha20Poly1305.Key? = null
    
    private var writeNonceCounter: Long = 0
    private var readNonceCounter: Long = 0

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

    suspend fun handshake(): PeerVerification {
        state = SecureChannelState.HANDSHAKING
        try {
            val myIdentity = identityStore.getOrGenerateIdentity()
            val myName = deviceInfoProvider.getDeviceName().encodeToByteArray()
            val myIdPubKeyBytes = myIdentity.keyPair.publicKey.encodeToByteArray(EdDSA.PublicKey.Format.RAW)
            
            val xdh = provider.get(XDH)
            val myEphKeyPair = xdh.keyPairGenerator(XDH.Curve.X25519).generateKey()
            val myEphPubKeyBytes = myEphKeyPair.publicKey.encodeToByteArray(XDH.PublicKey.Format.RAW) 
            
            val h1 = PROTOCOL_VERSION + 
                     shortToBytes(myIdPubKeyBytes.size) + myIdPubKeyBytes + 
                     shortToBytes(myEphPubKeyBytes.size) + myEphPubKeyBytes + 
                     shortToBytes(myName.size) + myName
                     
            val len1 = intToBytes(h1.size)
            rawConnection.write(len1 + h1, 0, len1.size + h1.size)
            
            val peerLenBuf = ByteArray(4)
            readExactlyFromRaw(peerLenBuf, 4)
            val peerLen = bytesToInt(peerLenBuf)
            if (peerLen <= 0 || peerLen > 10000) throw IllegalStateException("Invalid handshake frame size")
            
            val peerH1 = ByteArray(peerLen)
            readExactlyFromRaw(peerH1, peerLen)
            
            var offset = 0
            val peerVersion = peerH1.copyOfRange(offset, offset + PROTOCOL_VERSION.size)
            offset += PROTOCOL_VERSION.size
            if (!peerVersion.contentEquals(PROTOCOL_VERSION)) throw IllegalStateException("UNSUPPORTED_VERSION")
            
            val peerIdLen = bytesToShort(peerH1, offset); offset += 2
            val peerIdPubKeyBytes = peerH1.copyOfRange(offset, offset + peerIdLen); offset += peerIdLen
            
            val peerEphLen = bytesToShort(peerH1, offset); offset += 2
            val peerEphPubKeyBytes = peerH1.copyOfRange(offset, offset + peerEphLen); offset += peerEphLen
            
            val peerNameLen = bytesToShort(peerH1, offset); offset += 2
            val peerNameBytes = peerH1.copyOfRange(offset, offset + peerNameLen); offset += peerNameLen
            val peerNameStr = peerNameBytes.decodeToString()
            
            val peerIdPubKey = provider.get(EdDSA).publicKeyDecoder(EdDSA.Curve.Ed25519).decodeFromByteArray(EdDSA.PublicKey.Format.RAW, peerIdPubKeyBytes)
            val peerEphPubKey = xdh.publicKeyDecoder(XDH.Curve.X25519).decodeFromByteArray(XDH.PublicKey.Format.RAW, peerEphPubKeyBytes)
            val sharedSecret = myEphKeyPair.privateKey.sharedSecretGenerator().generateSharedSecret(peerEphPubKey)
            
            val isMyKeySmaller = compareByteArrays(myEphPubKeyBytes, peerEphPubKeyBytes) < 0
            
            val idA = if (isMyKeySmaller) myIdPubKeyBytes else peerIdPubKeyBytes
            val idB = if (isMyKeySmaller) peerIdPubKeyBytes else myIdPubKeyBytes
            val ephA = if (isMyKeySmaller) myEphPubKeyBytes else peerEphPubKeyBytes
            val ephB = if (isMyKeySmaller) peerEphPubKeyBytes else myEphPubKeyBytes
            
            val transcriptData = PROTOCOL_VERSION + 
                intToBytes(idA.size) + idA + 
                intToBytes(idB.size) + idB +
                intToBytes(ephA.size) + ephA + 
                intToBytes(ephB.size) + ephB
                
            val transcriptHash = provider.get(SHA256).hasher().hash(transcriptData)
            
            // Sign the transcript
            val mySignature = myIdentity.keyPair.privateKey.signatureGenerator().generateSignature(transcriptHash)
            val sigLenBytes = shortToBytes(mySignature.size)
            rawConnection.write(sigLenBytes + mySignature, 0, sigLenBytes.size + mySignature.size)
            
            val peerSigLenBuf = ByteArray(2)
            readExactlyFromRaw(peerSigLenBuf, 2)
            val peerSigLen = bytesToShort(peerSigLenBuf)
            if (peerSigLen <= 0 || peerSigLen > 2000) throw IllegalStateException("Invalid signature size")
            
            val peerSignature = ByteArray(peerSigLen)
            readExactlyFromRaw(peerSignature, peerSigLen)
            
            peerIdPubKey.signatureVerifier().verifySignature(transcriptHash, peerSignature)
            
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
            
            val peerDeviceId = calculateFingerprint(peerIdPubKeyBytes, provider)
            val knownPeer = trustedPeerStore.getPeer(peerDeviceId)
            
            if (knownPeer != null && knownPeer.publicKey.contentEquals(peerIdPubKeyBytes)) {
                state = SecureChannelState.ESTABLISHED
                trustedPeerStore.savePeer(knownPeer.copy(lastSeen = com.fastdrop.utils.getCurrentTimeMillis()))
                return PeerVerification.TrustedPeer(peerDeviceId, peerNameStr, peerIdPubKeyBytes)
            }
            
            state = SecureChannelState.WAITING_FOR_SAS_CONFIRMATION
            return PeerVerification.NewPeer(peerDeviceId, peerNameStr, peerDeviceId, sasString, peerIdPubKeyBytes)
            
        } catch (e: Exception) {
            state = SecureChannelState.FAILED
            rawConnection.close()
            throw e
        }
    }

    suspend fun confirmPeer(verification: PeerVerification.NewPeer) {
        if (state == SecureChannelState.WAITING_FOR_SAS_CONFIRMATION) {
            val peer = TrustedPeer(
                deviceId = verification.deviceId,
                publicKey = verification.publicKeyRaw,
                friendlyName = verification.friendlyName,
                firstSeen = com.fastdrop.utils.getCurrentTimeMillis(),
                lastSeen = com.fastdrop.utils.getCurrentTimeMillis()
            )
            trustedPeerStore.savePeer(peer)
            state = SecureChannelState.ESTABLISHED
        }
    }
    
    suspend fun confirmPeer() {
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
            val encryptedLen = bytesToInt(lenBytes)
            
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
            
            val lenBytes = intToBytes(expectedCipherLen)

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
