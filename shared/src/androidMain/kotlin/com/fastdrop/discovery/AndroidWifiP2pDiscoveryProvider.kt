package com.fastdrop.discovery

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class AndroidWifiP2pDiscoveryProvider(
    private val context: Context,
    private val wifiP2pManager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel
) : DiscoveryProvider {

    private val _peers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    override val peers: Flow<List<DiscoveredPeer>> get() = _peers

    private var isStarted = false
    private var receiver: BroadcastReceiver? = null

    @SuppressLint("MissingPermission")
    override suspend fun start() {
        if (isStarted) return
        isStarted = true

        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION) {
                    wifiP2pManager.requestPeers(channel) { peerList ->
                        val discovered = peerList?.deviceList?.map { device ->
                            DiscoveredPeer(
                                discoveryId = device.deviceAddress,
                                displayName = device.deviceName,
                                addresses = listOf(device.deviceAddress),
                                port = 0, // Port will be determined during connection
                                source = DiscoveryType.WIFI_DIRECT
                            )
                        } ?: emptyList()
                        _peers.value = discovered
                    }
                }
            }
        }
        
        context.registerReceiver(receiver, intentFilter)

        wifiP2pManager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d("FastDrop", "Wi-Fi Direct discovery started")
            }
            override fun onFailure(reasonCode: Int) {
                Log.e("FastDrop", "Wi-Fi Direct discovery failed: $reasonCode")
            }
        })
    }

    override suspend fun stop() {
        if (!isStarted) return
        isStarted = false

        receiver?.let { context.unregisterReceiver(it) }
        receiver = null

        wifiP2pManager.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) {}
        })

        _peers.value = emptyList()
    }
}
