package com.fastdrop

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.providers.jdk.JDK

actual fun createTestCryptographyProvider(): CryptographyProvider {
    return CryptographyProvider.JDK()
}
