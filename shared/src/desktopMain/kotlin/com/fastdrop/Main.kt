package com.fastdrop

import com.fastdrop.core.Peer
import com.fastdrop.core.TransportType
import com.fastdrop.transfer.FileMetadata
import com.fastdrop.transfer.TransferManager
import com.fastdrop.security.FileIdentityStore
import com.fastdrop.security.FileTrustedPeerStore
import com.fastdrop.security.DesktopDeviceInfoProvider
import com.fastdrop.security.PeerVerification
import com.fastdrop.security.SecureChannel
import com.fastdrop.transport.ManualLanTransport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import java.util.Scanner
import kotlin.system.exitProcess
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.roundToInt

fun main(): Unit = runBlocking {
    println("FastDrop TCP Demo")
    println("1. Listen (Receiver)")
    println("2. Connect (Sender)")
    print("Choice: ")
    
    val scanner = Scanner(System.`in`)
    val choice = scanner.nextLine().trim()
    
    val transport = ManualLanTransport(47832)
    val transferManager = TransferManager()
    val identityStore = FileIdentityStore(File(System.getProperty("user.home"), ".fastdrop/identity.json"))
    val trustedPeerStore = FileTrustedPeerStore(File(System.getProperty("user.home"), ".fastdrop/trusted_peers.json"))
    val deviceInfoProvider = DesktopDeviceInfoProvider()
    
    when (choice) {
        "1" -> {
            println("Listening on port 47832...")
            val connectionFlow = transport.startHosting()
            val rawConnection = connectionFlow.first()
            println("Connected by ${rawConnection.peer.address}")
            
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
            
            var targetFile: String? = null
            var tempFile: String? = null
            
            val success = transferManager.receiveFile(
                connection = secureChannel,
                onOfferReceived = { offer ->
                    println("Incoming file: ${offer.name} (${offer.size / 1024 / 1024} MB)")
                    val safeName = transferManager.sanitizeFilename(offer.name)
                    targetFile = safeName
                    tempFile = "$safeName.fastdrop-part"
                    true // always accept for demo
                },
                fileSink = FileSystem.SYSTEM.sink(tempFile!!.toPath()),
                onProgress = { current, total ->
                    val percent = if (total > 0) (current.toDouble() / total * 100).roundToInt() else 0
                    print("\rReceiving: $current / $total ($percent %)")
                }
            )
            
            println()
            if (success) {
                println("Transfer successful! Hash verified.")
                // Renaming part file safely
                Files.move(File(tempFile!!).toPath(), File(targetFile!!).toPath(), StandardCopyOption.REPLACE_EXISTING)
                println("File saved to $targetFile")
            } else {
                println("Transfer failed or hash mismatch.")
                File(tempFile!!).delete()
            }
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
            println("Connecting to $ip:47832...")
            
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
                
                val metadata = FileMetadata("transfer_${System.currentTimeMillis()}", file.name, file.length())
                
                val startTime = System.currentTimeMillis()
                var lastTime = startTime
                var lastBytes = 0L
                
                val success = transferManager.sendFile(
                    connection = secureChannel,
                    metadata = metadata,
                    fileSource = FileSystem.SYSTEM.source(file.toOkioPath()),
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
    exitProcess(0)
}

fun File.toOkioPath() = absolutePath.toPath()
