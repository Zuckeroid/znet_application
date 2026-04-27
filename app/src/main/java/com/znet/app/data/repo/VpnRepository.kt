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
import com.znet.app.data.remote.AppAutomationPolicy
import com.znet.app.data.remote.AppRoutingPolicy
import com.znet.app.data.remote.DeviceRegistrationData
import com.znet.app.data.remote.NoActiveAccessException
import com.znet.app.data.remote.OrchestratorClient
import com.znet.app.data.remote.TokenAccessResponse
import com.znet.app.data.remote.InvalidTokenException
import com.znet.app.vpn.AppAutomationMonitorService
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

            finalizeTokenExchange(access)
        }
    }

    suspend fun refreshAccessBundle(): Result<ResolvedNodeAccess> {
        return runCatching {
            val prefs = preferencesRepository.preferences.first()
            val authBaseUrl = BuildConfig.AUTH_API_URL.trimEnd('/')
            val sessionToken = prefs.deviceToken.trim()

            require(sessionToken.isNotBlank()) { "Session token is missing" }
            require(authBaseUrl.isNotBlank()) { INVALID_TOKEN_MESSAGE }

            val deviceData = buildDeviceRegistrationData()
            val access = orchestratorClient.resolveTokenAccess(
                baseUrl = authBaseUrl,
                token = sessionToken,
                deviceData = deviceData
            ).getOrThrow()

            finalizeSessionRefresh(
                access = access,
                currentDeviceToken = sessionToken
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
        routingPolicy: AppRoutingPolicy,
        automationPolicy: AppAutomationPolicy,
        autoDisconnectApps: Set<String>,
        latencyMs: Long
    ) {
        val allowedApps = routingAllowedApps(
            routingPolicy = routingPolicy,
            userExcludedApps = emptySet()
        )
        val disallowedApps = routingDisallowedApps(
            routingPolicy = routingPolicy,
            userExcludedApps = emptySet(),
            allowedApps = allowedApps
        )
        val effectiveAutoDisconnectApps =
            (automationPolicy.autoDisconnectApps + autoDisconnectApps).normalizePackageSet()

        val intent = Intent(context, ZnetVpnService::class.java).apply {
            action = ZnetVpnService.ACTION_CONNECT
            putExtra(ZnetVpnService.EXTRA_NODE, json.encodeToString(node))
            putExtra(ZnetVpnService.EXTRA_XRAY_CONFIG, xrayConfig)
            putExtra(ZnetVpnService.EXTRA_ALLOWED_APPS, allowedApps.toTypedArray())
            putExtra(ZnetVpnService.EXTRA_SPLIT_TUNNEL_APPS, disallowedApps.toTypedArray())
            putExtra(ZnetVpnService.EXTRA_AUTO_DISCONNECT_APPS, effectiveAutoDisconnectApps.toTypedArray())
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

    fun startAutomationMonitor() {
        val intent = Intent(context, AppAutomationMonitorService::class.java).apply {
            action = AppAutomationMonitorService.ACTION_START
        }
        context.startForegroundService(intent)
    }

    fun stopAutomationMonitor() {
        val intent = Intent(context, AppAutomationMonitorService::class.java).apply {
            action = AppAutomationMonitorService.ACTION_STOP
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

    private suspend fun finalizeTokenExchange(
        access: TokenAccessResponse
    ): ResolvedNodeAccess {
        val deviceToken = access.deviceToken
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: throw InvalidTokenException("Device session token is missing")
        preferencesRepository.setDeviceSession(deviceToken)
        return resolveNodeAccess(access)
    }

    private suspend fun finalizeSessionRefresh(
        access: TokenAccessResponse,
        currentDeviceToken: String
    ): ResolvedNodeAccess {
        val nextDeviceToken = access.deviceToken
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: currentDeviceToken
        preferencesRepository.setDeviceSession(nextDeviceToken)
        return resolveNodeAccess(
            access = access
        )
    }

    private fun resolveNodeAccess(access: TokenAccessResponse): ResolvedNodeAccess {
        if (access.hasActiveAccess == false) {
            throw NoActiveAccessException("No active access for this device")
        }
        if (access.connectionReady == false) {
            throw AccessNotReadyException(ACCESS_NOT_READY_MESSAGE)
        }
        if (access.xrayConfig.isNullOrBlank()) {
            throw AccessNotReadyException(ACCESS_NOT_READY_MESSAGE)
        }
        return NodeLinkConfigFactory.fromTokenAccess(access)
    }

    private fun routingAllowedApps(
        routingPolicy: AppRoutingPolicy,
        userExcludedApps: Set<String>
    ): Set<String> {
        if (routingPolicy.normalizedMode != AppRoutingPolicy.MODE_SELECTED_APPS) {
            return emptySet()
        }

        val excluded = userExcludedApps.normalizePackageSet() + context.packageName
        return routingPolicy.includedApps
            .normalizePackageSet()
            .filterNot { packageName -> excluded.contains(packageName) }
            .filter { packageName -> isInstalledPackage(packageName) }
            .toSet()
    }

    private fun routingDisallowedApps(
        routingPolicy: AppRoutingPolicy,
        userExcludedApps: Set<String>,
        allowedApps: Set<String>
    ): Set<String> {
        if (allowedApps.isNotEmpty()) {
            return emptySet()
        }

        val policyExcluded = when (routingPolicy.normalizedMode) {
            AppRoutingPolicy.MODE_ALL_EXCEPT -> routingPolicy.excludedApps
            AppRoutingPolicy.MODE_ALL_APPS -> routingPolicy.excludedApps
            else -> emptySet()
        }

        return (policyExcluded + userExcludedApps).normalizePackageSet()
    }

    private fun Iterable<String>.normalizePackageSet(): Set<String> {
        return map { item -> item.trim() }
            .filter { item -> item.isNotBlank() }
            .toSet()
    }

    private fun isInstalledPackage(packageName: String): Boolean {
        val pm = context.packageManager
        return runCatching {
            pm.getLaunchIntentForPackage(packageName) != null
        }.getOrDefault(false)
    }

    private companion object {
        const val REQUIRED_TOKEN_LENGTH = 32
        const val INVALID_TOKEN_MESSAGE = "Invalid token"
        const val ACCESS_NOT_READY_MESSAGE = "Access is not ready yet"
    }
}
