package com.fastdrop.discovery

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.util.Log
import com.fastdrop.FastDropConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class AndroidWifiP2pDiscoveryProvider(
    private val context: Context,
    private val wifiP2pManager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel
) : DiscoveryProvider {

    private val _peers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    override val peers: Flow<List<DiscoveredPeer>> get() = _peers

    private val currentDiscovered = mutableMapOf<String, DiscoveredPeer>()
    
    private var isStarted = false
    private var serviceRequest: WifiP2pDnsSdServiceRequest? = null
    private var receiver: BroadcastReceiver? = null

    @SuppressLint("MissingPermission")
    override suspend fun start() {
        if (isStarted) return
        isStarted = true

        // 1. Add Local Service (for Android <-> Android)
        val record = mapOf(
            "version" to "1",
            "port" to FastDropConfig.DEFAULT_PORT.toString()
        )
        val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance("FastDrop", "_fastdrop._tcp", record)
        wifiP2pManager.addLocalService(channel, serviceInfo, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d("FastDrop", "P2P Local service added")
            }
            override fun onFailure(reason: Int) {
                Log.e("FastDrop", "P2P Local service add failed: $reason")
            }
        })

        // 2. Setup DnsSdResponseListeners
        wifiP2pManager.setDnsSdResponseListeners(channel,
            WifiP2pManager.DnsSdServiceResponseListener { instanceName, registrationType, srcDevice ->
                if (instanceName.equals("FastDrop", ignoreCase = true) && registrationType.contains("_fastdrop._tcp")) {
                    val peer = DiscoveredPeer(
                        discoveryId = srcDevice.deviceAddress,
                        displayName = srcDevice.deviceName.takeIf { it.isNotBlank() } ?: "P2P Device",
                        addresses = listOf(srcDevice.deviceAddress),
                        port = FastDropConfig.DEFAULT_PORT,
                        source = DiscoveryType.WIFI_DIRECT,
                        protocolVersion = 1
                    )
                    synchronized(currentDiscovered) {
                        currentDiscovered[peer.discoveryId] = peer
                        _peers.value = currentDiscovered.values.toList()
                    }
                }
            },
            WifiP2pManager.DnsSdTxtRecordListener { fullDomainName, recordMap, srcDevice ->
                if (recordMap["version"] == "1") {
                    Log.d("FastDrop", "P2P Valid TXT record from ${srcDevice.deviceAddress}")
                    synchronized(currentDiscovered) {
                        val peer = currentDiscovered[srcDevice.deviceAddress]
                        if (peer != null) {
                            currentDiscovered[srcDevice.deviceAddress] = peer.copy(protocolVersion = 1)
                            _peers.value = currentDiscovered.values.toList()
                        }
                    }
                }
            }
        )

        // 3. Add Service Request
        serviceRequest = WifiP2pDnsSdServiceRequest.newInstance()
        wifiP2pManager.addServiceRequest(channel, serviceRequest, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                // 4. Discover Services
                wifiP2pManager.discoverServices(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.d("FastDrop", "P2P Service discovery started")
                    }
                    override fun onFailure(reason: Int) {
                        Log.e("FastDrop", "P2P Service discovery failed: $reason")
                    }
                })
            }
            override fun onFailure(reason: Int) {
                Log.e("FastDrop", "P2P Add service request failed: $reason")
            }
        })

        // 5. Fallback for Windows: Raw P2P Peer discovery
        val intentFilter = IntentFilter(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                wifiP2pManager.requestPeers(channel) { peerList: WifiP2pDeviceList? ->
                    peerList?.deviceList?.forEach { srcDevice ->
                        synchronized(currentDiscovered) {
                            // Only add if not already discovered via DNS-SD (DNS-SD is richer)
                            if (!currentDiscovered.containsKey(srcDevice.deviceAddress)) {
                                val peer = DiscoveredPeer(
                                    discoveryId = srcDevice.deviceAddress,
                                    displayName = srcDevice.deviceName.takeIf { it.isNotBlank() } ?: "Windows PC",
                                    addresses = listOf(srcDevice.deviceAddress),
                                    port = FastDropConfig.DEFAULT_PORT,
                                    source = DiscoveryType.WIFI_DIRECT,
                                    protocolVersion = 1 // Assumption for standard P2P
                                )
                                currentDiscovered[peer.discoveryId] = peer
                            }
                        }
                    }
                    synchronized(currentDiscovered) {
                        _peers.value = currentDiscovered.values.toList()
                    }
                }
            }
        }
        context.registerReceiver(receiver, intentFilter)

        wifiP2pManager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d("FastDrop", "Raw P2P peer discovery started")
            }
            override fun onFailure(reason: Int) {
                Log.e("FastDrop", "Raw P2P peer discovery failed: $reason")
            }
        })
    }

    override suspend fun stop() {
        if (!isStarted) return
        isStarted = false

        receiver?.let { context.unregisterReceiver(it) }
        receiver = null

        wifiP2pManager.clearLocalServices(channel, null)
        wifiP2pManager.clearServiceRequests(channel, null)
        wifiP2pManager.stopPeerDiscovery(channel, null)
        
        synchronized(currentDiscovered) {
            currentDiscovered.clear()
            _peers.value = emptyList()
        }
    }
}
