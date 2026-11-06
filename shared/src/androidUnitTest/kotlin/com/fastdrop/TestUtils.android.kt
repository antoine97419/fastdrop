package com.fastdrop

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.providers.jdk.JDK

actual fun createTestCryptographyProvider(): CryptographyProvider {
    // Android unit tests execute on the host JVM. Use a fresh JDK provider per
    // peer so ChaCha20 cipher state is not shared between both test endpoints.
    return CryptographyProvider.JDK()
}
