package com.fastdrop.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.fastdrop.FastDropConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class AndroidNsdDiscoveryProvider(
    context: Context,
    private val localFriendlyName: String,
    private val localDeviceId: String? = null
) : DiscoveryProvider {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val SERVICE_TYPE = "_fastdrop._tcp" // NSDManager adds .local. automatically on some versions, but searches for this type

    private val _peers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    override val peers: Flow<List<DiscoveredPeer>> get() = _peers

    private val currentDiscovered = mutableMapOf<String, DiscoveredPeer>()
    
    private var isStarted = false
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    override suspend fun start() {
        if (isStarted) return
        isStarted = true

        registerService()
        discoverServices()
    }

    override suspend fun stop() {
        if (!isStarted) return
        isStarted = false

        try {
            registrationListener?.let { nsdManager.unregisterService(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        registrationListener = null

        try {
            discoveryListener?.let { nsdManager.stopServiceDiscovery(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        discoveryListener = null

        synchronized(currentDiscovered) {
            currentDiscovered.clear()
            _peers.value = emptyList()
        }
    }

    private fun registerService() {
        val record = FastDropServiceRecord(1, localDeviceId, localFriendlyName, setOf("send", "receive"))
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = localFriendlyName
            serviceType = SERVICE_TYPE
            port = FastDropConfig.DEFAULT_PORT
            record.txt().forEach { (key, value) -> setAttribute(key, value) }
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                // localFriendlyName = NsdServiceInfo.serviceName // Handle name conflict resolution if needed
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e("FastDrop", "Registration failed: $errorCode")
            }
            override fun onServiceUnregistered(arg0: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }

        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    private fun discoverServices() {
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}

            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceName == localFriendlyName || service.serviceName.startsWith("$localFriendlyName-")) {
                    return // Ignore self
                }
                
                // Resolve the service to get IP/Port
                nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.e("FastDrop", "Resolve failed: $errorCode")
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val txt = listOf("version", "deviceId", "name", "capabilities")
                            .associateWith { key -> serviceInfo.attributes[key]?.toString(Charsets.UTF_8) ?: "" }
                        val record = FastDropServiceRecord.parse(txt, serviceInfo.serviceName) ?: return

                        val address = serviceInfo.host?.hostAddress ?: return
                        val peer = DiscoveredPeer(
                            discoveryId = serviceInfo.serviceName,
                            displayName = record.name,
                            addresses = listOf(address),
                            port = serviceInfo.port,
                            source = DiscoveryType.MDNS,
                            protocolVersion = record.version,
                            isFastDropService = true,
                            deviceId = record.deviceId,
                            capabilities = record.capabilities
                        )
                        
                        synchronized(currentDiscovered) {
                            currentDiscovered[peer.discoveryId] = peer
                            _peers.value = currentDiscovered.values.toList()
                        }
                    }
                })
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                synchronized(currentDiscovered) {
                    if (currentDiscovered.remove(service.serviceName) != null) {
                        _peers.value = currentDiscovered.values.toList()
                    }
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                nsdManager.stopServiceDiscovery(this)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                nsdManager.stopServiceDiscovery(this)
            }
        }

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }
}
