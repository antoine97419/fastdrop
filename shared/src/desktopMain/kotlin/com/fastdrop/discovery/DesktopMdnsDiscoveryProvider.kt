package com.fastdrop.discovery

import com.fastdrop.FastDropConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/** Publishes and browses FastDrop on every usable local IPv4 interface. */
class DesktopMdnsDiscoveryProvider(
    private val localFriendlyName: String,
    private val localDeviceId: String? = null
) : DiscoveryProvider {
    private val serviceType = "_fastdrop._tcp.local."
    private val instances = mutableListOf<JmDNS>()
    private val found = mutableMapOf<String, DiscoveredPeer>()
    private val state = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    override val peers: Flow<List<DiscoveredPeer>> = state
    private var started = false

    fun advertisedRecord() = FastDropServiceRecord(
        1, localDeviceId, localFriendlyName, setOf("send", "receive")
    )

    private fun key(event: ServiceEvent) = event.dns.inetAddress.hostAddress + "/" + event.name
    private val listener = object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) {
            event.dns.requestServiceInfo(event.type, event.name, 1)
        }
        override fun serviceRemoved(event: ServiceEvent) {
            synchronized(found) {
                found.remove(key(event))
                state.value = found.values.distinctBy { it.deviceId ?: it.discoveryId }
            }
        }
        override fun serviceResolved(event: ServiceEvent) {
            val info = event.info ?: return
            val txt = listOf("version", "deviceId", "name", "capabilities")
                .associateWith { info.getPropertyString(it) ?: "" }
            val record = FastDropServiceRecord.parse(txt, info.name) ?: return
            if (record.deviceId != null && record.deviceId == localDeviceId) return
            if (record.deviceId == null && record.name == localFriendlyName) return
            val addresses = info.inet4Addresses.map { it.hostAddress }
            if (addresses.isEmpty()) return
            val peer = DiscoveredPeer(info.name, record.name, addresses, info.port,
                DiscoveryType.MDNS, record.version, true, record.deviceId, record.capabilities)
            synchronized(found) {
                found[key(event)] = peer
                state.value = found.values.distinctBy { it.deviceId ?: it.discoveryId }
            }
        }
    }

    override suspend fun start() = withContext(Dispatchers.IO) {
        if (started) return@withContext
        started = true
        val interfaces = NetworkInterface.getNetworkInterfaces().toList().filter { iface ->
            runCatching {
                val label = (iface.name + " " + iface.displayName).lowercase()
                iface.isUp && !iface.isLoopback && iface.supportsMulticast() &&
                    listOf("tailscale", "vpn", "virtual", "veth", "docker", "loopback").none { it in label }
            }.getOrDefault(false)
        }
        interfaces.flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLinkLocalAddress && !it.isLoopbackAddress }
            .distinctBy { it.hostAddress }
            .forEach { address ->
                try {
                    val dns = JmDNS.create(address)
                    val info = ServiceInfo.create(serviceType, localFriendlyName,
                        FastDropConfig.DEFAULT_PORT, 0, 0, advertisedRecord().txt())
                    dns.registerService(info)
                    dns.addServiceListener(serviceType, listener)
                    instances.add(dns)
                } catch (failure: Exception) {
                    System.err.println("FastDrop discovery unavailable on " + address.hostAddress + ": " + failure.message)
                }
            }
        if (instances.isEmpty()) System.err.println("FastDrop: no usable LAN interface for discovery")
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        if (!started) return@withContext
        started = false
        instances.forEach { dns ->
            runCatching { dns.removeServiceListener(serviceType, listener) }
            runCatching { dns.unregisterAllServices() }
            runCatching { dns.close() }
        }
        instances.clear()
        synchronized(found) { found.clear(); state.value = emptyList() }
    }
}
