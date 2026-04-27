package com.znet.app.data.repo

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import com.znet.app.BuildConfig
import com.znet.app.data.UserPreferencesRepository
import com.znet.app.data.model.InstalledApp
import com.znet.app.data.model.ServerNode
import com.znet.app.data.remote.AccessNotReadyException
import com.znet.app.data.remote.DeviceRegistrationData
import com.znet.app.data.remote.NoActiveAccessException
import com.znet.app.data.remote.OrchestratorClient
import com.znet.app.data.remote.TokenAccessResponse
import com.znet.app.vpn.ZnetVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class VpnRepository(
    private val context: Context,
    private val preferencesRepository: UserPreferencesRepository,
    private val orchestratorClient: OrchestratorClient,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    suspend fun authenticateWithToken(token: String): Result<ResolvedNodeAccess> {
        return runCatching {
            val cleanToken = token.trim()
            val authBaseUrl = BuildConfig.AUTH_API_URL.trimEnd('/')

            require(cleanToken.isNotBlank()) { INVALID_TOKEN_MESSAGE }
            require(cleanToken.length == REQUIRED_TOKEN_LENGTH) { INVALID_TOKEN_MESSAGE }
            require(authBaseUrl.isNotBlank()) { INVALID_TOKEN_MESSAGE }

            val deviceData = buildDeviceRegistrationData()
            val access = orchestratorClient.resolveTokenAccess(
                baseUrl = authBaseUrl,
                token = cleanToken,
                deviceData = deviceData
            ).getOrThrow()

            finalizeAuthentication(
                access = access,
                authBaseUrl = authBaseUrl,
                fallbackToken = cleanToken
            )
        }
    }

    suspend fun refreshAccessBundle(): Result<ResolvedNodeAccess> {
        return runCatching {
            val prefs = preferencesRepository.preferences.first()
            val authBaseUrl = BuildConfig.AUTH_API_URL.trimEnd('/')
            val sessionToken = prefs.deviceToken.trim().ifBlank { prefs.authToken.trim() }

            require(sessionToken.isNotBlank()) { "токен не найден" }
            require(authBaseUrl.isNotBlank()) { INVALID_TOKEN_MESSAGE }

            val deviceData = buildDeviceRegistrationData()
            val access = orchestratorClient.resolveTokenAccess(
                baseUrl = authBaseUrl,
                token = sessionToken,
                deviceData = deviceData
            ).getOrThrow()

            finalizeAuthentication(
                access = access,
                authBaseUrl = authBaseUrl,
                fallbackToken = sessionToken
            )
        }
    }

    suspend fun clearSession() {
        preferencesRepository.clearAuthSession()
    }

    suspend fun loadInstalledApps(): List<InstalledApp> = withContext(Dispatchers.Default) {
        val pm = context.packageManager
        pm.getInstalledApplications(0)
            .asSequence()
            .filter { app ->
                app.packageName != context.packageName &&
                    pm.getLaunchIntentForPackage(app.packageName) != null &&
                    (app.flags and ApplicationInfo.FLAG_SYSTEM == 0)
            }
            .map { app ->
                InstalledApp(
                    packageName = app.packageName,
                    label = pm.getApplicationLabel(app).toString()
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    fun startVpn(
        node: ServerNode,
        xrayConfig: String,
        splitTunnelApps: Set<String>,
        autoDisconnectApps: Set<String>,
        latencyMs: Long
    ) {
        val intent = Intent(context, ZnetVpnService::class.java).apply {
            action = ZnetVpnService.ACTION_CONNECT
            putExtra(ZnetVpnService.EXTRA_NODE, json.encodeToString(node))
            putExtra(ZnetVpnService.EXTRA_XRAY_CONFIG, xrayConfig)
            putExtra(ZnetVpnService.EXTRA_SPLIT_TUNNEL_APPS, splitTunnelApps.toTypedArray())
            putExtra(ZnetVpnService.EXTRA_AUTO_DISCONNECT_APPS, autoDisconnectApps.toTypedArray())
            putExtra(ZnetVpnService.EXTRA_LATENCY_MS, latencyMs)
        }
        context.startForegroundService(intent)
    }

    fun stopVpn() {
        val intent = Intent(context, ZnetVpnService::class.java).apply {
            action = ZnetVpnService.ACTION_DISCONNECT
        }
        context.startService(intent)
    }

    private suspend fun buildDeviceRegistrationData(): DeviceRegistrationData {
        val installId = preferencesRepository.getOrCreateDeviceId()
        val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
        val model = Build.MODEL?.trim().orEmpty()
        val brand = Build.BRAND?.trim().orEmpty()

        val deviceName = when {
            model.isBlank() && manufacturer.isBlank() && brand.isBlank() -> "Android device"
            manufacturer.isNotBlank() && model.startsWith(manufacturer, ignoreCase = true) -> model
            brand.isNotBlank() && model.startsWith(brand, ignoreCase = true) -> model
            manufacturer.isNotBlank() && model.isNotBlank() -> "$manufacturer $model"
            brand.isNotBlank() && model.isNotBlank() -> "$brand $model"
            model.isNotBlank() -> model
            manufacturer.isNotBlank() -> manufacturer
            else -> brand
        }

        return DeviceRegistrationData(
            deviceName = deviceName.take(255),
            platform = "android",
            installId = installId.take(255)
        )
    }

    private suspend fun finalizeAuthentication(
        access: TokenAccessResponse,
        authBaseUrl: String,
        fallbackToken: String
    ): ResolvedNodeAccess {
        val sessionToken = access.deviceToken
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: access.issuedToken
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: fallbackToken

        preferencesRepository.setOrchestrator(authBaseUrl, sessionToken)
        preferencesRepository.clearManualVlessLink()
        preferencesRepository.setTokenAuthMetadata(
            deviceToken = access.deviceToken,
            hasActiveAccess = access.hasActiveAccess,
            activeSubscriptionLinks = access.activeSubscriptionLinks,
            filteredSubscriptionLinks = access.filteredSubscriptionLinks,
            selectedSubscriptionLink = access.selectedSubscriptionLink
        )
        return resolveNodeAccess(
            access = access
        )
    }

    private fun resolveNodeAccess(access: TokenAccessResponse): ResolvedNodeAccess {
        if (access.hasActiveAccess == false) {
            throw NoActiveAccessException("у токена нет активного доступа")
        }
        if (access.connectionReady == false) {
            throw AccessNotReadyException(ACCESS_NOT_READY_MESSAGE)
        }
        if (access.xrayConfig.isNullOrBlank()) {
            throw AccessNotReadyException(ACCESS_NOT_READY_MESSAGE)
        }
        return NodeLinkConfigFactory.fromTokenAccess(access)
    }

    private companion object {
        const val REQUIRED_TOKEN_LENGTH = 32
        const val INVALID_TOKEN_MESSAGE = "некорректный токен"
        const val ACCESS_NOT_READY_MESSAGE = "доступ еще не готов"
    }
}
