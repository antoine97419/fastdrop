package com.fastdrop

import com.fastdrop.core.Peer
import com.fastdrop.core.TransportType
import com.fastdrop.discovery.DesktopMdnsDiscoveryProvider
import com.fastdrop.discovery.DiscoveryManager
import com.fastdrop.security.DesktopDeviceInfoProvider
import com.fastdrop.security.FileIdentityStore
import com.fastdrop.security.FileTrustedPeerStore
import com.fastdrop.security.PeerVerification
import com.fastdrop.security.SecureChannel
import com.fastdrop.transfer.DesktopIncomingFileDestination
import com.fastdrop.transfer.DesktopTransferFileSource
import com.fastdrop.transfer.TransferManager
import com.fastdrop.transport.ManualLanTransport
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import java.io.File
import java.util.Scanner
import kotlin.math.roundToInt
import kotlin.system.exitProcess

fun main(): Unit = runBlocking {
    println("FastDrop TCP Demo")
    println("1. Listen (Receiver)")
    println("2. Connect (Sender)")
    print("Choice: ")

    val scanner = Scanner(System.`in`)
    val choice = scanner.nextLine().trim()

    val transport = ManualLanTransport(FastDropConfig.DEFAULT_PORT)
    val transferManager = TransferManager()
    val homeDir = System.getProperty("fastdrop.home", System.getProperty("user.home") + "/.fastdrop")
    val identityStore = FileIdentityStore(File(homeDir, "identity.json"))
    val trustedPeerStore = FileTrustedPeerStore(File(homeDir, "trusted_peers.json"))
    val deviceInfoProvider = DesktopDeviceInfoProvider()
    val discoveryManager = DiscoveryManager(listOf(DesktopMdnsDiscoveryProvider(deviceInfoProvider.getDeviceName())))

    when (choice) {
        "1" -> {
            var targetFile: String? = null

            val receiver = PersistentReceiverServer(
                startHosting = { transport.startHosting() },
                createSecureChannel = { rawConnection ->
                    println("Connected by ${rawConnection.peer.address}")
                    SecureChannel(rawConnection, identityStore, trustedPeerStore, deviceInfoProvider)
                },
                authenticate = { secureChannel ->
                    println("Authenticating device...")
                    when (val verification = secureChannel.handshake()) {
                        is PeerVerification.TrustedPeer -> {
                            println("Trusted device:\n${verification.friendlyName} ✓")
                            println("Secure connection established.")
                            true
                        }
                        is PeerVerification.NewPeer -> {
                            println("New device detected:\n${verification.friendlyName}")
                            println("Fingerprint:\n${verification.fingerprint}")
                            println("=====================================")
                            println(" SECURITY CODE: ${verification.sas}")
                            println("=====================================")
                            print("Codes match and trust this device? [y/N] ")
                            if (scanner.nextLine().trim().equals("y", ignoreCase = true)) {
                                secureChannel.confirmPeer(verification)
                                println("Secure channel established.")
                                true
                            } else {
                                false
                            }
                        }
                    }
                },
                receiveTransfer = { secureChannel ->
                    targetFile = null
                    val success = transferManager.receiveFile(
                        connection = secureChannel,
                        onOfferReceived = { offer ->
                            println("Incoming file: ${offer.name} (${offer.size / 1024 / 1024} MB)")
                            val safeName = transferManager.sanitizeFilename(offer.name)
                            targetFile = safeName
                            DesktopIncomingFileDestination(File(safeName))
                        },
                        onProgress = { current, total ->
                            val percent = if (total > 0) (current.toDouble() / total * 100).roundToInt() else 0
                            print("\rReceiving: $current / $total ($percent %)")
                        }
                    )
                    println()
                    if (success) println("File saved to $targetFile")
                    success
                },
                closeSecureChannel = { it.close() },
                closeRawConnection = { it.close() },
                stopHosting = { transport.stop() },
                onListening = { println("Listening for connections...") },
                onSessionMessage = { println(it) },
                onCloseError = { println("Failed to close client session: ${it.message}") }
            )

            val shutdownHook = Thread {
                runBlocking {
                    receiver.stop()
                    discoveryManager.stopAll()
                }
            }
            Runtime.getRuntime().addShutdownHook(shutdownHook)

            try {
                receiver.run()
            } finally {
                receiver.stop()
                discoveryManager.stopAll()
                runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
            }

            return@runBlocking
        }
        "2" -> {
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
            println("Connecting to $ip:FastDropConfig.DEFAULT_PORT...")

            try {
                val rawConnection = transport.connect(peer)
                println("Connected.")

                val secureChannel = SecureChannel(rawConnection, identityStore, trustedPeerStore, deviceInfoProvider)
                println("Authenticating device...")
                val verification = secureChannel.handshake()

                when (verification) {
                    is PeerVerification.TrustedPeer -> {
                        println("Trusted device:\n${verification.friendlyName} ✓")
                        println("Secure connection established.")
                    }
                    is PeerVerification.NewPeer -> {
                        println("New device detected:\n${verification.friendlyName}")
                        println("Fingerprint:\n${verification.fingerprint}")
                        println("=====================================")
                        println(" SECURITY CODE: ${verification.sas}")
                        println("=====================================")
                        print("Codes match and trust this device? [y/N] ")
                        val confirm = scanner.nextLine().trim()
                        if (confirm.equals("y", ignoreCase = true)) {
                            secureChannel.confirmPeer(verification)
                            println("Secure channel established.")
                        } else {
                            println("ABORT: Connection refused.")
                            secureChannel.close()
                            exitProcess(1)
                        }
                    }
                }

                val startTime = System.currentTimeMillis()
                var lastTime = startTime
                var lastBytes = 0L

                val success = transferManager.sendFile(
                    connection = secureChannel,
                    fileSource = DesktopTransferFileSource(file),
                    onProgress = { current, total ->
                        val now = System.currentTimeMillis()
                        if (now - lastTime > 500 || current == total) {
                            val percent = if (total > 0) (current.toDouble() / total * 100).roundToInt() else 0
                            val speed = (current - lastBytes) / ((now - lastTime) / 1000.0) / 1024 / 1024
                            print("\rSending: ${current / 1024 / 1024} MB / ${total / 1024 / 1024} MB ($percent %) - Speed: ${String.format("%.1f", speed)} MB/s")
                            lastTime = now
                            lastBytes = current
                        }
                    }
                )

                println()
                if (success) {
                    println("Transfer complete & verified by receiver!")
                } else {
                    println("Transfer failed!")
                }

                secureChannel.close()
            } catch (e: Exception) {
                println("Connection failed: ${e.message}")
            }
        }
        else -> println("Invalid choice.")
    }

    transport.stop()
    discoveryManager.stopAll()
    exitProcess(0)
}

fun File.toOkioPath() = absolutePath.toPath()
