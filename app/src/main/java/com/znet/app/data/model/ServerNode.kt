package com.znet.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ServerNode(
    val id: String,
    val name: String,
    val country: String,
    val city: String,
    val flagEmoji: String,
    val host: String,
    val port: Int,
    val healthUrl: String? = null,
    val priority: Int = 0
)
