package com.fastdrop.discovery

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
}
