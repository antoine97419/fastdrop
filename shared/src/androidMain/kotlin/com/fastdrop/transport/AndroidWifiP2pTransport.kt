package com.fastdrop.transport

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import com.fastdrop.FastDropConfig
import com.fastdrop.core.Peer
import com.fastdrop.core.TransportType
import com.fastdrop.discovery.AndroidWifiP2pDiscoveryProvider
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class AndroidWifiP2pTransport(
    private val context: Context,
    private val wifiP2pManager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel,
    private val discoveryProvider: AndroidWifiP2pDiscoveryProvider,
    private val port: Int = FastDropConfig.DEFAULT_PORT
) : Transport {

    override val type = TransportType.WIFI_DIRECT
    private val selectorManager = SelectorManager(Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    
    private val incomingConnections = Channel<Connection>(Channel.UNLIMITED)
    
    // Si connect() a été appelé, on résout cette continuation lors de la formation du groupe
    private var pendingConnectContinuation: kotlin.coroutines.Continuation<Connection>? = null
    private var pendingPeer: Peer? = null

    private var receiver: BroadcastReceiver? = null
    private val transportScope = CoroutineScope(Dispatchers.IO + Job())

    override suspend fun discover(): Flow<List<Peer>> {
        return discoveryProvider.peers.map { discoveredPeers ->
            discoveredPeers.map { dp ->
                Peer(
                    id = dp.discoveryId,
                    name = dp.displayName ?: "Unknown",
                    transportType = TransportType.WIFI_DIRECT,
                    address = dp.addresses.firstOrNull() ?: ""
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun connect(peer: Peer): Connection {
        require(peer.transportType == TransportType.WIFI_DIRECT) { "Invalid transport type" }

        val config = WifiP2pConfig().apply {
            deviceAddress = peer.address
        }

        return suspendCoroutine { cont ->
            pendingConnectContinuation = cont
            pendingPeer = peer
            
            wifiP2pManager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d("FastDrop", "Wi-Fi Direct connection initiated to ${peer.address}")
                    // La suite est gérée dans le BroadcastReceiver (WIFI_P2P_CONNECTION_CHANGED_ACTION)
                }

                override fun onFailure(reason: Int) {
                    pendingConnectContinuation = null
                    pendingPeer = null
                    cont.resumeWithException(Exception("Wi-Fi Direct connect failed: $reason"))
                }
            })
        }
    }

    override suspend fun startHosting(): Flow<Connection> = flow {
        if (receiver == null) {
            registerReceiver()
        }
        
        for (conn in incomingConnections) {
            emit(conn)
        }
    }

    private fun registerReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) {
                    val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    
                    if (networkInfo?.isConnected == true) {
                        wifiP2pManager.requestConnectionInfo(channel) { info ->
                            handleConnectionInfo(info)
                        }
                    } else {
                        // Déconnexion ou groupe perdu
                        Log.d("FastDrop", "Wi-Fi Direct group disconnected")
                        serverSocket?.close()
                        serverSocket = null
                    }
                }
            }
        }
        context.registerReceiver(receiver, intentFilter)
    }

    private fun handleConnectionInfo(info: WifiP2pInfo?) {
        if (info == null || !info.groupFormed) return
        
        transportScope.launch {
            try {
                val connection: Connection
                val targetPeer = pendingPeer ?: Peer(id = info.groupOwnerAddress?.hostAddress ?: "unknown", name = "P2P Peer", transportType = TransportType.WIFI_DIRECT, address = info.groupOwnerAddress?.hostAddress ?: "")

                if (info.isGroupOwner) {
                    Log.d("FastDrop", "We are Group Owner, waiting for TCP connection on port $port...")
                    if (serverSocket == null) {
                        serverSocket = aSocket(selectorManager).tcp().bind("0.0.0.0", port)
                    }
                    val socket = serverSocket!!.accept()
                    connection = KtorWifiConnection(targetPeer, socket)
                    
                    // On ne ferme pas le serverSocket ici au cas où (bien que P2P soit souvent 1-à-1 ici)
                } else {
                    val goAddress = info.groupOwnerAddress?.hostAddress ?: throw Exception("Unknown GO address")
                    Log.d("FastDrop", "We are Client, connecting to GO at $goAddress:$port...")
                    
                    // Attente courte pour s'assurer que le GO a eu le temps de bind son ServerSocket
                    kotlinx.coroutines.delay(500) 
                    
                    val socket = aSocket(selectorManager).tcp().connect(goAddress, port)
                    connection = KtorWifiConnection(targetPeer, socket)
                }

                val cont = pendingConnectContinuation
                if (cont != null) {
                    pendingConnectContinuation = null
                    pendingPeer = null
                    cont.resume(connection)
                } else {
                    incomingConnections.send(connection)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                val cont = pendingConnectContinuation
                if (cont != null) {
                    pendingConnectContinuation = null
                    pendingPeer = null
                    cont.resumeWithException(e)
                }
            }
        }
    }

    override suspend fun stop() {
        receiver?.let { context.unregisterReceiver(it) }
        receiver = null
        
        serverSocket?.close()
        serverSocket = null
        
        wifiP2pManager.removeGroup(channel, null)
        selectorManager.close()
    }
}

class KtorWifiConnection(
    override val peer: Peer,
    private val socket: Socket
) : Connection {
    
    private val readChannel = socket.openReadChannel()
    private val writeChannel = socket.openWriteChannel(autoFlush = true)

    override suspend fun read(buffer: ByteArray): Int {
        return readChannel.readAvailable(buffer, 0, buffer.size)
    }

    override suspend fun write(data: ByteArray, offset: Int, length: Int) {
        writeChannel.writeFully(data, offset, length)
    }

    override suspend fun close() {
        socket.close()
    }
}
