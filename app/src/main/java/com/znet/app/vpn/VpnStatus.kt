package com.znet.app.vpn

import com.znet.app.data.model.ConnectionState
import com.znet.app.data.model.ServerNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VpnStatus(
    val state: ConnectionState = ConnectionState.DISCONNECTED,
    val currentNode: ServerNode? = null,
    val latencyMs: Long = -1,
    val publicIp: String = "",
    val rxBytes: Long = 0,
    val txBytes: Long = 0,
    val lastUpdatedMs: Long = System.currentTimeMillis(),
    val errorMessage: String? = null
)

object VpnStatusBus {
    private val mutableStatus = MutableStateFlow(VpnStatus())
    val status: StateFlow<VpnStatus> = mutableStatus.asStateFlow()

    fun update(transform: (VpnStatus) -> VpnStatus) {
        mutableStatus.value = transform(mutableStatus.value).copy(lastUpdatedMs = System.currentTimeMillis())
    }

    fun set(value: VpnStatus) {
        mutableStatus.value = value.copy(lastUpdatedMs = System.currentTimeMillis())
    }
}
