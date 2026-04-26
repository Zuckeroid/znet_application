package com.znet.app.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.znet.app.ZnetApp
import com.znet.app.data.model.TelemetryPayload
import com.znet.app.data.model.ConnectionState
import com.znet.app.vpn.VpnStatusBus
import kotlinx.coroutines.flow.first

class StatsWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val app = applicationContext as ZnetApp
        val prefs = app.container.preferencesRepository.preferences.first()
        if (prefs.authToken.isBlank() || prefs.orchestratorBaseUrl.isBlank()) {
            return Result.success()
        }

        val status = VpnStatusBus.status.value
        val nodeId = status.currentNode?.id ?: prefs.selectedNodeId ?: ""
        if (nodeId.isBlank()) {
            return Result.success()
        }

        val payload = TelemetryPayload(
            deviceId = if (prefs.deviceId.isBlank()) app.container.preferencesRepository.getOrCreateDeviceId() else prefs.deviceId,
            nodeId = nodeId,
            connected = status.state == ConnectionState.CONNECTED,
            state = status.state.name,
            rxBytes = status.rxBytes,
            txBytes = status.txBytes,
            latencyMs = status.latencyMs,
            adaptiveEnabled = prefs.adaptiveEnabled,
            timestampMs = System.currentTimeMillis()
        )

        return app.container.orchestratorClient.postTelemetry(
            baseUrl = prefs.orchestratorBaseUrl,
            token = prefs.authToken,
            payload = payload
        ).fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() }
        )
    }
}
