package com.fastdrop.security

import android.os.Build

class AndroidDeviceInfoProvider : DeviceInfoProvider {
    override suspend fun getDeviceName(): String {
        return "${Build.MANUFACTURER} ${Build.MODEL}"
    }
}
