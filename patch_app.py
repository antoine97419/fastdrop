import sys

with open("androidApp/build.gradle.kts", "r") as f:
    text = f.read()

text += """
dependencies {
    implementation("dev.whyoleg.cryptography:cryptography-provider-optimal:0.6.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
}
"""

with open("androidApp/build.gradle.kts", "w") as f:
    f.write(text)

with open("androidApp/src/main/java/com/fastdrop/android/MainActivity.kt", "r") as f:
    text = f.read()

text = text.replace("import androidx.activity.result.contract.ActivityResultContracts", "import androidx.activity.result.contract.ActivityResultContracts\nimport androidx.lifecycle.lifecycleScope")

text = text.replace("val mDNS = AndroidNsdDiscoveryProvider(this, deviceInfoProvider.getDeviceName())", "val deviceName = kotlinx.coroutines.runBlocking { deviceInfoProvider.getDeviceName() }\n        val mDNS = AndroidNsdDiscoveryProvider(this, deviceName)")

old_trust = """                        } else {
                            secureChannel.confirmPeer(verification as PeerVerification.TrustedPeer)
                            withContext(Dispatchers.Main) { appState.value = "Trusted peer connected." }
                        }"""
new_trust = """                        } else {
                            // nothing
                            withContext(Dispatchers.Main) { appState.value = "Trusted peer connected." }
                        }"""
text = text.replace(old_trust, new_trust)

old_trust2 = """                } else {
                    secureChannel.confirmPeer(verification as PeerVerification.TrustedPeer)
                }"""
new_trust2 = """                } else {
                    // nothing
                }"""
text = text.replace(old_trust2, new_trust2)

with open("androidApp/src/main/java/com/fastdrop/android/MainActivity.kt", "w") as f:
    f.write(text)
