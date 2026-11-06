package com.fastdrop.security

class DesktopDeviceInfoProvider : DeviceInfoProvider {
    override suspend fun getDeviceName(): String =
        (System.getProperty("fastdrop.device.name") ?: System.getenv("COMPUTERNAME")
        ?: System.getenv("HOSTNAME") ?: "Windows PC")
            .trim().trimEnd('?', ' ').takeIf { it.isNotBlank() } ?: "Windows PC"
}
