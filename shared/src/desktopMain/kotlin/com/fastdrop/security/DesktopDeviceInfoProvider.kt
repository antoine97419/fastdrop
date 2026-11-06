package com.fastdrop.security

class DesktopDeviceInfoProvider : DeviceInfoProvider {
    override suspend fun getDeviceName(): String = "Windows PC"
}
