package com.fastdrop.discovery

import com.fastdrop.FastDropConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/**
 * mDNS Discovery for Desktop/JVM using JmDNS.
 *
 * Limitation multi-interface MVP:
 * Nous énumérons toutes les interfaces réseau (non loopback, actives) et créons
 * une instance JmDNS par interface IPv4 trouvée. Cela garantit que la découverte
 * fonctionne sur Wi-Fi, Ethernet, et virtuels (Tailscale etc.), même si annoncer
 * sur des VPN n'est pas toujours souhaitable.
 */
class DesktopMdnsDiscoveryProvider(
    private val localFriendlyName: String
) : DiscoveryProvider {

    private val SERVICE_TYPE = "_fastdrop._tcp.local."
    private val jmdnsInstances = mutableListOf<JmDNS>()
    
    private val _peers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    override val peers: Flow<List<DiscoveredPeer>> get() = _peers
    
    private val currentDiscovered = mutableMapOf<String, DiscoveredPeer>()

    private val listener = object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) {
            event.dns.requestServiceInfo(event.type, event.name, 1)
        }

        override fun serviceRemoved(event: ServiceEvent) {
            synchronized(currentDiscovered) {
                // Event.name est le nom de l'instance, par ex "PC-Antoine"
                val discoveryId = event.name
                if (currentDiscovered.remove(discoveryId) != null) {
                    _peers.value = currentDiscovered.values.toList()
                }
            }
        }

        override fun serviceResolved(event: ServiceEvent) {
            val info = event.info ?: return
            
            // Ignorer notre propre service (basé sur le nom)
            // Dans un réseau réel, on pourrait avoir le même nom que qqun d'autre (conflit mDNS),
            // mais jmDNS renomme avec un suffixe.
            if (info.name == localFriendlyName || info.name.startsWith("$localFriendlyName-")) {
                // Heuristique basique pour s'ignorer, MVP.
                return
            }

            val addresses = info.hostAddresses.toList()
            if (addresses.isEmpty()) return
            
            val version = info.getPropertyString("version")
            if (version != "1") return // Incompatible

            val peer = DiscoveredPeer(
                discoveryId = info.name,
                displayName = info.name,
                addresses = addresses,
                port = info.port,
                source = DiscoveryType.MDNS
            )

            synchronized(currentDiscovered) {
                currentDiscovered[peer.discoveryId] = peer
                _peers.value = currentDiscovered.values.toList()
            }
        }
    }

    override suspend fun start() {
        withContext(Dispatchers.IO) {
            val interfaces = NetworkInterface.getNetworkInterfaces().toList()
            val validAddresses = mutableListOf<InetAddress>()
            
            for (iface in interfaces) {
                if (iface.isLoopback || !iface.isUp) continue
                // On peut filtrer ici (ex: ignorer 'tailscale0' ou 'docker0' si on veut)
                for (addr in iface.inetAddresses.toList()) {
                    if (addr is java.net.Inet4Address) {
                        validAddresses.add(addr)
                    }
                }
            }
            
            // Fallback s'il n'y a pas d'interface valide
            if (validAddresses.isEmpty()) {
                validAddresses.add(InetAddress.getLocalHost())
            }

            for (addr in validAddresses) {
                try {
                    val jmdns = JmDNS.create(addr)
                    jmdnsInstances.add(jmdns)
                    
                    val serviceInfo = ServiceInfo.create(
                        SERVICE_TYPE,
                        localFriendlyName,
                        FastDropConfig.DEFAULT_PORT,
                        0,
                        0,
                        mapOf("version" to "1")
                    )
                    jmdns.registerService(serviceInfo)
                    jmdns.addServiceListener(SERVICE_TYPE, listener)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    override suspend fun stop() {
        withContext(Dispatchers.IO) {
            jmdnsInstances.forEach { jmdns ->
                try {
                    jmdns.removeServiceListener(SERVICE_TYPE, listener)
                    jmdns.unregisterAllServices()
                    jmdns.close()
                } catch (e: Exception) {
                    // Ignore
                }
            }
            jmdnsInstances.clear()
            
            synchronized(currentDiscovered) {
                currentDiscovered.clear()
                _peers.value = emptyList()
            }
        }
    }
}
