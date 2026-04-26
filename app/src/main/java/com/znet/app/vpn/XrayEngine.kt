package com.znet.app.vpn

interface XrayEngine {
    suspend fun ensureReady()
    suspend fun start(configJson: String, tunFd: Int? = null)
    suspend fun stop()
}
