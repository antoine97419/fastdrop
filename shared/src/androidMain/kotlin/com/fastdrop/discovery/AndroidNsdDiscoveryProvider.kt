package com.fastdrop.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.fastdrop.FastDropConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class AndroidNsdDiscoveryProvider private constructor(
    context: Context,
    private val localFriendlyName: String,
    private val localDeviceId: String
) : DiscoveryProvider {
    private val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val serviceType = "_fastdrop._tcp"
    private val state = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    override val peers: Flow<List<DiscoveredPeer>> = state
    private val found = mutableMapOf<String, DiscoveredPeer>()
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var unregisterRequested = false
    private var unregisterAttempts = 0
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val lifecycle = NsdRegistrationLifecycle(
        register = ::registerService,
        unregister = ::unregisterService,
        browse = ::discoverServices,
        stopBrowsing = ::stopDiscovery,
        onError = { Log.e(TAG, "[Discovery][Android] restart failed", it) }
    )

    override suspend fun start() = lifecycle.start()
    override suspend fun stop() = lifecycle.stop()

    private fun registerService() {
        check(registrationListener == null) { "NSD registration is still active" }
        val record = FastDropServiceRecord(1, localDeviceId, localFriendlyName, setOf("send", "receive"))
        val info = NsdServiceInfo().apply {
            serviceName = localFriendlyName
            serviceType = this@AndroidNsdDiscoveryProvider.serviceType
            port = FastDropConfig.DEFAULT_PORT
            record.txt().forEach { (key, value) -> setAttribute(key, value) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "[Discovery][Android] service registered name=${serviceInfo.serviceName}")
                lifecycle.registered()
                if (lifecycle.state == NsdRegistrationLifecycle.State.STOPPING && !unregisterRequested)
                    unregisterService()
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "[Discovery][Android] register failed code=$errorCode")
                registrationListener = null
                unregisterRequested = false
                unregisterAttempts = 0
                lifecycle.registrationFailed()
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "[Discovery][Android] service unregistered name=${serviceInfo.serviceName}")
                registrationListener = null
                unregisterRequested = false
                unregisterAttempts = 0
                lifecycle.unregistered()
            }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "[Discovery][Android] unregister failed code=$errorCode")
                unregisterRequested = false
                if (unregisterAttempts < 3) unregisterService()
                // Keep STOPPING after repeated failures to avoid a duplicate registration.
            }
        }
        registrationListener = listener
        unregisterRequested = false
        unregisterAttempts = 0
        Log.d(TAG, "[Discovery][Android] register requested")
        try {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (error: Exception) {
            registrationListener = null
            throw error
        }
    }

    private fun unregisterService() {
        val listener = registrationListener ?: run {
            lifecycle.unregistered()
            return
        }
        if (unregisterRequested) return
        Log.d(TAG, "[Discovery][Android] unregister requested")
        try {
            unregisterAttempts++
            unregisterRequested = true
            nsdManager.unregisterService(listener)
        } catch (error: Exception) {
            unregisterRequested = false
            Log.e(TAG, "[Discovery][Android] unregister request failed", error)
            // Keep the listener and STOPPING until Android confirms cleanup.
        }
    }

    private fun discoverServices() {
        check(discoveryListener == null) { "NSD browse is still active" }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "[Discovery][Android] browse started")
            }
            override fun onServiceFound(service: NsdServiceInfo) {
                if (discoveryListener !== this) return
                val browseListener = this
                nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        Log.d(TAG, "[Discovery][Android] resolve failed code=$errorCode")
                    }
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        if (discoveryListener !== browseListener) return
                        val txt = listOf("version", "deviceId", "name", "capabilities")
                            .associateWith { key -> info.attributes[key]?.toString(Charsets.UTF_8) ?: "" }
                        val record = FastDropServiceRecord.parse(txt, info.serviceName) ?: return
                        if (record.deviceId == localDeviceId) {
                            Log.d(TAG, "[Discovery][Android] ignoring local peer deviceId=${record.deviceId}")
                            return
                        }
                        val address = info.host?.hostAddress ?: return
                        val peer = DiscoveredPeer(info.serviceName, record.name, listOf(address), info.port,
                            DiscoveryType.MDNS, record.version, true, record.deviceId, record.capabilities)
                        synchronized(found) {
                            found[info.serviceName] = peer
                            state.value = mergeDiscoveredPeers(found.values.toList(), localDeviceId) {
                                Log.d(TAG, "[Discovery] merged duplicate peer deviceId=$it")
                            }
                        }
                    }
                })
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                synchronized(found) {
                    found.remove(service.serviceName)
                    state.value = mergeDiscoveredPeers(found.values.toList(), localDeviceId)
                }
            }
            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "[Discovery][Android] browse stopped")
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "[Discovery][Android] browse failed code=$errorCode")
                stopDiscovery()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "[Discovery][Android] browse stop failed code=$errorCode")
            }
        }
        discoveryListener = listener
        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (error: Exception) {
            discoveryListener = null
            throw error
        }
    }

    private fun stopDiscovery() {
        val listener = discoveryListener
        discoveryListener = null
        if (listener != null) runCatching { nsdManager.stopServiceDiscovery(listener) }
            .onFailure { Log.e(TAG, "[Discovery][Android] browse stop request failed", it) }
        synchronized(found) { found.clear(); state.value = emptyList() }
    }

    companion object {
        private const val TAG = "FastDrop"
        @Volatile private var shared: AndroidNsdDiscoveryProvider? = null

        fun getShared(context: Context, friendlyName: String, deviceId: String): AndroidNsdDiscoveryProvider =
            synchronized(this) {
                shared ?: AndroidNsdDiscoveryProvider(context.applicationContext, friendlyName, deviceId)
                    .also { shared = it }
            }
    }
}
