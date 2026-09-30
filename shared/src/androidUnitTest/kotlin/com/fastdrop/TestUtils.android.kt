package com.fastdrop

import dev.whyoleg.cryptography.CryptographyProvider

actual fun createTestCryptographyProvider(): CryptographyProvider {
    return CryptographyProvider.Default
}
