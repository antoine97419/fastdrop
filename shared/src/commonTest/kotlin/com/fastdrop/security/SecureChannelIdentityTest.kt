package com.fastdrop.security

import com.fastdrop.core.Peer
import com.fastdrop.core.TransportType
import com.fastdrop.createInMemoryConnectionPair
import dev.whyoleg.cryptography.CryptographyProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class SecureChannelIdentityTest {
    private fun createPeers() = Peer("1", "P1", TransportType.LAN, "127.0.0.1") to Peer("2", "P2", TransportType.LAN, "127.0.0.2")

    @Test
    fun testFirstConnectionAndReconnection() = runTest {
        val (peer1, peer2) = createPeers()
        
        val p1 = com.fastdrop.createTestCryptographyProvider()
        val p2 = com.fastdrop.createTestCryptographyProvider()

        val idStore1 = TestIdentityStore(p1)
        val idStore2 = TestIdentityStore(p2)
        
        val trustStore1 = TestTrustedPeerStore()
        val trustStore2 = TestTrustedPeerStore()

        // --- SESSION 1: FIRST CONNECTION ---
        val (conn1_1, conn2_1) = createInMemoryConnectionPair(peer1, peer2)
        val sec1_1 = SecureChannel(conn1_1, idStore1, trustStore1, TestDeviceInfoProvider("Alice"), p1)
        val sec2_1 = SecureChannel(conn2_1, idStore2, trustStore2, TestDeviceInfoProvider("Bob"), p2)

        val job1 = async(Dispatchers.Default) { sec1_1.handshake() }
        val res2_1 = sec2_1.handshake()
        val res1_1 = job1.await()

        assertIs<PeerVerification.NewPeer>(res1_1)
        assertIs<PeerVerification.NewPeer>(res2_1)
        
        assertEquals(res1_1.sas, res2_1.sas)
        
        sec1_1.confirmPeer(res1_1)
        sec2_1.confirmPeer(res2_1)
        
        assertEquals(SecureChannelState.ESTABLISHED, sec1_1.state)
        
        assertNotNull(trustStore1.getPeer(res1_1.deviceId))
        assertNotNull(trustStore2.getPeer(res2_1.deviceId))

        // --- SESSION 2: RECONNECTION ---
        val (conn1_2, conn2_2) = createInMemoryConnectionPair(peer1, peer2)
        val sec1_2 = SecureChannel(conn1_2, idStore1, trustStore1, TestDeviceInfoProvider("Alice"), p1)
        val sec2_2 = SecureChannel(conn2_2, idStore2, trustStore2, TestDeviceInfoProvider("Bob"), p2)

        val job2 = async(Dispatchers.Default) { sec1_2.handshake() }
        val res2_2 = sec2_2.handshake()
        val res1_2 = job2.await()

        // Should be TrustedPeer immediately! No SAS needed.
        assertIs<PeerVerification.TrustedPeer>(res1_2)
        assertIs<PeerVerification.TrustedPeer>(res2_2)
        
        assertEquals(SecureChannelState.ESTABLISHED, sec1_2.state)
    }
    
    @Test
    fun testSameNameDifferentKeyRejected() = runTest {
        val (peer1, peer2) = createPeers()
        
        val p1 = com.fastdrop.createTestCryptographyProvider()
        val p2 = com.fastdrop.createTestCryptographyProvider()
        val p3 = com.fastdrop.createTestCryptographyProvider()

        val idStore1 = TestIdentityStore(p1)
        val idStore2 = TestIdentityStore(p2) // Original Bob
        val idStore3 = TestIdentityStore(p3) // Impersonator Bob
        
        val trustStore1 = TestTrustedPeerStore()
        
        // --- SESSION 1: Alice meets Bob ---
        val (conn1_1, conn2_1) = createInMemoryConnectionPair(peer1, peer2)
        val sec1_1 = SecureChannel(conn1_1, idStore1, trustStore1, TestDeviceInfoProvider("Alice"), p1)
        val sec2_1 = SecureChannel(conn2_1, idStore2, TestTrustedPeerStore(), TestDeviceInfoProvider("Bob"), p2)

        val job1 = async(Dispatchers.Default) { sec1_1.handshake() as PeerVerification.NewPeer }
        val res2_1 = sec2_1.handshake() as PeerVerification.NewPeer
        val res1_1 = job1.await()
        sec1_1.confirmPeer(res1_1)
        
        // --- SESSION 2: Alice meets Impersonator Bob ---
        val (conn1_2, conn2_2) = createInMemoryConnectionPair(peer1, peer2)
        val sec1_2 = SecureChannel(conn1_2, idStore1, trustStore1, TestDeviceInfoProvider("Alice"), p1)
        val sec2_2 = SecureChannel(conn2_2, idStore3, TestTrustedPeerStore(), TestDeviceInfoProvider("Bob"), p3)

        val job2 = async(Dispatchers.Default) { sec1_2.handshake() }
        val res2_2 = sec2_2.handshake()
        val res1_2 = job2.await()

        // MUST be NewPeer because the fingerprint changed, even if the name is "Bob"
        assertIs<PeerVerification.NewPeer>(res1_2)
    }
}
