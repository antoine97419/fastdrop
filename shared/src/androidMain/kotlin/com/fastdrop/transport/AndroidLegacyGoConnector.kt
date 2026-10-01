package com.fastdrop.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.util.Log
import com.fastdrop.FastDropConfig
import com.fastdrop.core.Peer
import com.fastdrop.core.TransportType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import android.os.Build
import android.annotation.SuppressLint

@SuppressLint("NewApi")
class AndroidLegacyGoConnector(private val context: Context) {

    suspend fun connect(ssid: String = "DIRECT-FD-FastDropPC", passphrase: String = "fastdrop123"): Connection {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(passphrase)
            .build()
            
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        return suspendCancellableCoroutine { cont ->
            Log.d("FastDrop", "LegacyGoConnector: Requesting network $ssid...")
            
            val callback = object : ConnectivityManager.NetworkCallback() {
                private var tcpAttempted = false
                private var currentSocket: Socket? = null

                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    Log.d("FastDrop", "LegacyGoConnector: onAvailable called. Waiting for LinkProperties...")
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    super.onLinkPropertiesChanged(network, linkProperties)
                    if (tcpAttempted) return

                    val ipv4 = linkProperties.linkAddresses.firstOrNull { it.address is Inet4Address }
                    if (ipv4 == null) {
                        Log.d("FastDrop", "LegacyGoConnector: LinkProperties changed but no IPv4 yet.")
                        return
                    }
                    
                    tcpAttempted = true
                    val currentCallback = this
                    
                    Thread {
                        try {
                            Log.d("FastDrop", "LegacyGoConnector: IPv4 obtained: ${ipv4.address.hostAddress}. Creating explicitly bound TCP Socket...")
                            val socket = network.socketFactory.createSocket()
                            currentSocket = socket
                            
                            socket.connect(InetSocketAddress("192.168.137.1", FastDropConfig.DEFAULT_PORT), 15_000)
                            Log.d("FastDrop", "LegacyGoConnector: TCP Connected! Local Address: ${socket.localSocketAddress}")
                            
                            val peer = Peer(
                                id = "windows-legacy-go",
                                name = "Windows PC",
                                transportType = TransportType.WIFI_DIRECT,
                                address = "192.168.137.1"
                            )
                            
                            val connection = AndroidNetworkConnection(
                                peer = peer,
                                socket = socket,
                                connectivityManager = connectivityManager,
                                networkCallback = currentCallback
                            )
                            
                            cont.resume(connection)
                        } catch (e: Exception) {
                            Log.e("FastDrop", "LegacyGoConnector: TCP connection failed", e)
                            cleanup(connectivityManager, currentCallback, currentSocket)
                            if (cont.isActive) cont.resumeWithException(e)
                        }
                    }.start()
                }

                override fun onUnavailable() {
                    super.onUnavailable()
                    Log.e("FastDrop", "LegacyGoConnector: onUnavailable (timeout/rejected)")
                    cleanup(connectivityManager, this, null)
                    if (cont.isActive) cont.resumeWithException(Exception("Network unavailable (timeout or rejected by user)"))
                }

                override fun onLost(network: Network) {
                    super.onLost(network)
                    Log.e("FastDrop", "LegacyGoConnector: onLost - network connection lost")
                    // If we lose network before connection is established:
                    if (cont.isActive) cont.resumeWithException(Exception("Network lost during connection"))
                    // If connection was already established, the Socket read/write will fail natively and trigger connection drop.
                }
            }
            
            cont.invokeOnCancellation {
                cleanup(connectivityManager, callback, null)
            }
            
            try {
                connectivityManager.requestNetwork(request, callback)
            } catch (e: SecurityException) {
                Log.e("FastDrop", "LegacyGoConnector: SecurityException", e)
                if (cont.isActive) cont.resumeWithException(e)
            } catch (e: Exception) {
                Log.e("FastDrop", "LegacyGoConnector: Exception", e)
                if (cont.isActive) cont.resumeWithException(e)
            }
        }
    }
    
    private fun cleanup(cm: ConnectivityManager, callback: ConnectivityManager.NetworkCallback, socket: Socket?) {
        try { socket?.close() } catch (e: Exception) {}
        try { cm.unregisterNetworkCallback(callback) } catch (e: Exception) {}
    }
}

class AndroidNetworkConnection(
    override val peer: Peer,
    private val socket: Socket,
    private val connectivityManager: ConnectivityManager,
    private val networkCallback: ConnectivityManager.NetworkCallback
) : Connection {
    
    private val inputStream = socket.getInputStream()
    private val outputStream = socket.getOutputStream()

    override suspend fun read(buffer: ByteArray): Int = withContext(Dispatchers.IO) {
        val bytesRead = inputStream.read(buffer)
        if (bytesRead == -1) -1 else bytesRead
    }

    override suspend fun write(data: ByteArray, offset: Int, length: Int) = withContext(Dispatchers.IO) {
        outputStream.write(data, offset, length)
        outputStream.flush()
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        Log.d("FastDrop", "AndroidNetworkConnection: Closing socket and unregistering network callback")
        try { socket.close() } catch (e: Exception) {}
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (e: Exception) {}
    }
}
