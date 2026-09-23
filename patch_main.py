import sys

with open("shared/src/desktopMain/kotlin/com/fastdrop/Main.kt", "r") as f:
    text = f.read()

text = text.replace("import com.fastdrop.transfer.TransferManager", """import com.fastdrop.transfer.TransferManager
import com.fastdrop.security.FileIdentityStore
import com.fastdrop.security.FileTrustedPeerStore
import com.fastdrop.security.DesktopDeviceInfoProvider
import com.fastdrop.security.PeerVerification
import com.fastdrop.security.SecureChannel""")

setup_stores = """    val identityStore = FileIdentityStore(File(System.getProperty("user.home"), ".fastdrop/identity.json"))
    val trustedPeerStore = FileTrustedPeerStore(File(System.getProperty("user.home"), ".fastdrop/trusted_peers.json"))
    val deviceInfoProvider = DesktopDeviceInfoProvider()"""

text = text.replace("    val transferManager = TransferManager()", "    val transferManager = TransferManager()\n" + setup_stores)

# Replace Receiver Handshake
old_recv = """            val secureChannel = com.fastdrop.security.SecureChannel(rawConnection)
            println("Performing secure handshake...")
            val handshake = secureChannel.handshake()
            
            println("=====================================")
            println(" SECURITY CODE: ${handshake.sas}")
            println("=====================================")
            print("Does the other device display the same code? [y/N] ")
            val confirm = scanner.nextLine().trim()
            if (confirm.equals("y", ignoreCase = true)) {
                secureChannel.confirmPeer()
                println("Secure channel established.")
            } else {
                println("ABORT: Connection refused.")
                secureChannel.close()
                exitProcess(1)
            }"""

new_recv = """            val secureChannel = SecureChannel(rawConnection, identityStore, trustedPeerStore, deviceInfoProvider)
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
            }"""

text = text.replace(old_recv, new_recv)

# Replace Sender Handshake
old_sender = """                val secureChannel = com.fastdrop.security.SecureChannel(rawConnection)
                println("Performing secure handshake...")
                val handshake = secureChannel.handshake()
                
                println("=====================================")
                println(" SECURITY CODE: ${handshake.sas}")
                println("=====================================")
                print("Does the other device display the same code? [y/N] ")
                val confirm = scanner.nextLine().trim()
                if (confirm.equals("y", ignoreCase = true)) {
                    secureChannel.confirmPeer()
                    println("Secure channel established.")
                } else {
                    println("ABORT: Connection refused.")
                    secureChannel.close()
                    exitProcess(1)
                }"""

new_sender = """                val secureChannel = SecureChannel(rawConnection, identityStore, trustedPeerStore, deviceInfoProvider)
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
                }"""

text = text.replace(old_sender, new_sender)

with open("shared/src/desktopMain/kotlin/com/fastdrop/Main.kt", "w") as f:
    f.write(text)
