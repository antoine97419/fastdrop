package com.fastdrop.discovery

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TestDiscoveryProvider : DiscoveryProvider {
    private val _peers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    override val peers: Flow<List<DiscoveredPeer>> = _peers
    
    var started = false
    var stopped = false
    
    override suspend fun start() { started = true }
    override suspend fun stop() { stopped = true }
    
    fun updatePeers(newList: List<DiscoveredPeer>) {
        _peers.value = newList
    }
}

class DiscoveryManagerTest {
    @Test
    fun testStartAndStop() = runTest {
        val provider1 = TestDiscoveryProvider()
        val provider2 = TestDiscoveryProvider()
        
        val manager = DiscoveryManager(listOf(provider1, provider2))
        
        manager.startAll()
        assertEquals(true, provider1.started)
        assertEquals(true, provider2.started)
        
        manager.stopAll()
        assertEquals(true, provider1.stopped)
        assertEquals(true, provider2.stopped)
    }
    
    @Test
    fun testPeerAggregationAndUpdates() = runTest {
        val provider = TestDiscoveryProvider()
        val manager = DiscoveryManager(listOf(provider))
        
        val peerA = DiscoveredPeer("idA", "Device A", listOf("192.168.1.10"), 47832, DiscoveryType.MDNS)
        val peerB = DiscoveredPeer("idB", "Device B", listOf("10.0.0.5"), 47832, DiscoveryType.MDNS)
        
        // 1. Ajout d'un peer
        provider.updatePeers(listOf(peerA))
        kotlinx.coroutines.delay(50)
        assertEquals(1, manager.peers.first().size)
        assertEquals("idA", manager.peers.first()[0].discoveryId)
        
        // 2. Mise à jour (doublons remplacés ou ajoutés)
        val peerAUpdated = peerA.copy(displayName = "Device A Updated")
        provider.updatePeers(listOf(peerAUpdated, peerB))
        kotlinx.coroutines.delay(50)
        val currentPeers = manager.peers.first()
        assertEquals(2, currentPeers.size)
        assertEquals("Device A Updated", currentPeers.find { it.discoveryId == "idA" }?.displayName)
        
        // 3. Suppression
        provider.updatePeers(listOf(peerAUpdated))
        kotlinx.coroutines.delay(50)
        assertEquals(1, manager.peers.first().size)
    }
    @Test
    fun combinesProvidersAndPrefersVerifiedService() = runTest {
        val mdns = TestDiscoveryProvider()
        val wifi = TestDiscoveryProvider()
        val manager = DiscoveryManager(listOf(mdns, wifi))
        val raw = DiscoveredPeer("pc", "DIRECT-printer", listOf("mac"), 47832, DiscoveryType.WIFI_DIRECT)
        val verified = raw.copy(displayName = "Windows PC", source = DiscoveryType.MDNS, isFastDropService = true)
        val phone = DiscoveredPeer("phone", "Pixel", listOf("ip"), 47832, DiscoveryType.MDNS, isFastDropService = true)
        wifi.updatePeers(listOf(raw))
        mdns.updatePeers(listOf(verified, phone))
        val peers = manager.peers.first { it.size == 2 }
        assertEquals("Windows PC", peers.first { it.discoveryId == "pc" }.displayName)
        assertEquals(true, peers.first { it.discoveryId == "pc" }.isFastDropService)
    }

    @Test fun discoveryKeepsUpdatingAfterTransferLifecycle() = runTest {
        val provider = TestDiscoveryProvider()
        val manager = DiscoveryManager(listOf(provider))
        val peer = DiscoveredPeer("pc", "Windows PC", listOf("192.168.1.5"), 47832,
            DiscoveryType.MDNS, isFastDropService = true)
        manager.startAll()
        assertEquals(emptyList(), manager.peers.first())
        provider.updatePeers(listOf(peer))
        assertEquals(listOf(peer), manager.peers.first { it.isNotEmpty() })
        provider.updatePeers(emptyList())
        assertEquals(emptyList(), manager.peers.first())
        assertEquals(true, provider.started)
        assertEquals(false, provider.stopped)
        provider.updatePeers(listOf(peer))
        assertEquals(listOf(peer), manager.peers.first { it.isNotEmpty() })
    }

    @Test fun deduplicatesPublicIdentityAcrossProviders() = runTest {
        val first = TestDiscoveryProvider()
        val second = TestDiscoveryProvider()
        val id = "AB:".repeat(31) + "AB"
        first.updatePeers(listOf(DiscoveredPeer("lan", "Pixel", listOf("192.168.1.2"),
            47832, DiscoveryType.MDNS, isFastDropService = true, deviceId = id)))
        second.updatePeers(listOf(DiscoveredPeer("other", "Pixel", listOf("10.0.0.2"),
            47832, DiscoveryType.MDNS, isFastDropService = true, deviceId = id)))
        assertEquals(1, DiscoveryManager(listOf(first, second)).peers.first { it.isNotEmpty() }.size)
    }

    @Test fun filtersSelfByIdButKeepsAnotherDeviceWithSameName() {
        val local = "AA:".repeat(31) + "AA"
        val other = "BB:".repeat(31) + "BB"
        val peers = listOf(
            DiscoveredPeer("self-suffixed", "Google Pixel 7a (2)", listOf("192.168.1.2"),
                47832, DiscoveryType.MDNS, isFastDropService = true, deviceId = local),
            DiscoveredPeer("other", "Google Pixel 7a", listOf("192.168.1.3"),
                47832, DiscoveryType.MDNS, isFastDropService = true, deviceId = other)
        )
        assertEquals(listOf(other), mergeDiscoveredPeers(peers, local).map { it.deviceId })
    }

    @Test fun mergesDuplicateIdsButNotMatchingNames() {
        val first = "AA:".repeat(31) + "AA"
        val second = "BB:".repeat(31) + "BB"
        val peers = listOf(
            DiscoveredPeer("service-1", "Pixel", listOf("192.168.1.2"), 47832,
                DiscoveryType.MDNS, isFastDropService = true, deviceId = first),
            DiscoveredPeer("service-2", "Pixel (2)", listOf("192.168.1.4"), 47832,
                DiscoveryType.MDNS, isFastDropService = true, deviceId = first),
            DiscoveredPeer("service-3", "Pixel", listOf("192.168.1.5"), 47832,
                DiscoveryType.MDNS, isFastDropService = true, deviceId = second)
        )
        val merged = mergeDiscoveredPeers(peers)
        assertEquals(2, merged.size)
        assertEquals(setOf("192.168.1.2", "192.168.1.4"),
            merged.first { it.deviceId == first }.addresses.toSet())
    }
}
