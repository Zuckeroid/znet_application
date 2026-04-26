package com.znet.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class TelemetryPayload(
    val deviceId: String,
    val nodeId: String,
    val connected: Boolean,
    val state: String,
    val rxBytes: Long,
    val txBytes: Long,
    val latencyMs: Long,
    val adaptiveEnabled: Boolean,
    val timestampMs: Long
)
