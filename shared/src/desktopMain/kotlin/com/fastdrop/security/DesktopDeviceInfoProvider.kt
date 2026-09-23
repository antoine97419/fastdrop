package com.fastdrop.security

class DesktopDeviceInfoProvider : DeviceInfoProvider {
    override suspend fun getDeviceName(): String {
        return System.getProperty("user.name", "Unknown User") + "'s PC"
    }
}
