import sys
import re

with open("shared/src/desktopMain/kotlin/com/fastdrop/Main.kt", "r") as f:
    text = f.read()

# Add Discovery imports
text = text.replace("import com.fastdrop.transfer.TransferManager", """import com.fastdrop.transfer.TransferManager
import com.fastdrop.discovery.DiscoveryManager
import com.fastdrop.discovery.DesktopMdnsDiscoveryProvider
import com.fastdrop.discovery.DiscoveredPeer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel""")

# Initialize DiscoveryManager
old_setup = "    val deviceInfoProvider = DesktopDeviceInfoProvider()"
new_setup = """    val deviceInfoProvider = DesktopDeviceInfoProvider()
    val discoveryManager = DiscoveryManager(listOf(DesktopMdnsDiscoveryProvider(deviceInfoProvider.getDeviceName())))"""
text = text.replace(old_setup, new_setup)

# Sender logic modification
old_sender = """        "2" -> {
            print("Destination IP: ")
            val ip = scanner.nextLine().trim()
            print("File path to send: ")
            val filePath = scanner.nextLine().trim()
            
            val file = File(filePath)
            if (!file.exists()) {
                println("File does not exist.")
                exitProcess(1)
            }
            
            val peer = Peer(id = ip, name = "Target", transportType = TransportType.LAN, address = ip)
            println("Connecting to $ip:${FastDropConfig.DEFAULT_PORT}...")
            
            try {
                val rawConnection = transport.connect(peer)
                println("Connected.")
                
                val secureChannel = SecureChannel(rawConnection, identityStore, trustedPeerStore, deviceInfoProvider)"""

new_sender = """        "2" -> {
            println("Searching for nearby devices...")
            var discoveredList = emptyList<DiscoveredPeer>()
            val searchJob = launch {
                discoveryManager.startAll()
                discoveryManager.peers.collect { peers ->
                    discoveredList = peers
                }
            }
            
            delay(2000) // Give mDNS 2 seconds to find peers
            
            println("\nNearby devices:")
            if (discoveredList.isEmpty()) {
                println(" (None found)")
            } else {
                discoveredList.forEachIndexed { index, p ->
                    println("[${index + 1}] ${p.displayName}")
                }
            }
            println("[0] Connect manually by IP")
            
            print("\nSelect device: ")
            val deviceChoiceStr = scanner.nextLine().trim()
            val deviceChoice = deviceChoiceStr.toIntOrNull() ?: -1
            
            val targetIp: String
            val targetName: String
            
            if (deviceChoice == 0) {
                print("Enter IP: ")
                targetIp = scanner.nextLine().trim()
                targetName = "Target"
            } else if (deviceChoice > 0 && deviceChoice <= discoveredList.size) {
                val p = discoveredList[deviceChoice - 1]
                targetIp = p.addresses.firstOrNull() ?: run {
                    println("No IP address found for this device.")
                    exitProcess(1)
                }
                targetName = p.displayName ?: "Target"
            } else {
                println("Invalid choice.")
                exitProcess(1)
            }
            
            searchJob.cancel()
            discoveryManager.stopAll()
            
            print("File path to send: ")
            val filePath = scanner.nextLine().trim()
            val file = File(filePath)
            if (!file.exists()) {
                println("File does not exist.")
                exitProcess(1)
            }
            
            val peer = Peer(id = targetIp, name = targetName, transportType = TransportType.LAN, address = targetIp)
            println("Connecting to $targetIp:${FastDropConfig.DEFAULT_PORT}...")
            
            try {
                val rawConnection = transport.connect(peer)
                println("Connected.")
                
                val secureChannel = SecureChannel(rawConnection, identityStore, trustedPeerStore, deviceInfoProvider)"""

text = text.replace(old_sender, new_sender)

with open("shared/src/desktopMain/kotlin/com/fastdrop/Main.kt", "w") as f:
    f.write(text)
