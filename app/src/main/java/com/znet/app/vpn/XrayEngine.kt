package com.znet.app.vpn

import android.os.ParcelFileDescriptor

interface XrayEngine {
    suspend fun ensureReady()
    suspend fun start(configJson: String, tunInterface: ParcelFileDescriptor? = null)
    suspend fun stop()
}
