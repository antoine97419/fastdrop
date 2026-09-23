package com.fastdrop

import com.fastdrop.core.Peer
import com.fastdrop.core.TransportType
import com.fastdrop.transfer.FileMetadata
import com.fastdrop.transfer.TransferManager
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

fun main() = runBlocking {
    println("FastDrop TCP Demo")
    println("1. Listen (Receiver)")
    println("2. Connect (Sender)")
    print("Choice: ")
    
    val scanner = Scanner(System.`in`)
    val choice = scanner.nextLine().trim()
    
    val transport = ManualLanTransport(47832)
    val transferManager = TransferManager()
    
    when (choice) {
        "1" -> {
            println("Listening on port 47832...")
            val connectionFlow = transport.startHosting()
            val connection = connectionFlow.first()
            println("Connected by ${connection.peer.address}")
            
            var targetFile: String? = null
            var tempFile: String? = null
            
            val success = transferManager.receiveFile(
                connection = connection,
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
                val connection = transport.connect(peer)
                println("Connected.")
                
                val metadata = FileMetadata("transfer_${System.currentTimeMillis()}", file.name, file.length())
                
                val startTime = System.currentTimeMillis()
                var lastTime = startTime
                var lastBytes = 0L
                
                val success = transferManager.sendFile(
                    connection = connection,
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
                
                connection.close()
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
