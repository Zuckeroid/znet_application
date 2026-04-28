package com.znet.app.data.repo

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import android.util.Log
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
import com.znet.app.data.remote.TokenAuthRequestException
import com.znet.app.data.remote.InvalidTokenException
import com.znet.app.vpn.AppAutomationMonitorService
import com.znet.app.vpn.ZnetVpnService
import java.io.IOException
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

            require(cleanToken.isNotBlank()) { INVALID_TOKEN_MESSAGE }
            require(cleanToken.length == REQUIRED_TOKEN_LENGTH) { INVALID_TOKEN_MESSAGE }

            val deviceData = buildDeviceRegistrationData()
            val resolved = resolveTokenAccessWithDomainPool(
                token = cleanToken,
                deviceData = deviceData
            )
            rememberSuccessfulDomain(resolved)

            finalizeTokenExchange(resolved.access)
        }
    }

    suspend fun refreshAccessBundle(): Result<ResolvedNodeAccess> {
        return runCatching {
            val prefs = preferencesRepository.preferences.first()
            val sessionToken = prefs.deviceToken.trim()

            require(sessionToken.isNotBlank()) { INVALID_TOKEN_MESSAGE }

            val deviceData = buildDeviceRegistrationData()
            val resolved = resolveTokenAccessWithDomainPool(
                token = sessionToken,
                deviceData = deviceData
            )
            rememberSuccessfulDomain(resolved)

            finalizeSessionRefresh(
                access = resolved.access,
                currentDeviceToken = sessionToken
            )
        }
    }

    suspend fun clearSession() {
        preferencesRepository.clearAuthSession()
    }

    suspend fun loadInstalledApps(): List<InstalledApp> = withContext(Dispatchers.Default) {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        pm.queryIntentActivities(launcherIntent, 0)
            .asSequence()
            .mapNotNull { resolved ->
                val app = resolved.activityInfo?.applicationInfo ?: return@mapNotNull null
                val isUserApp = (app.flags and ApplicationInfo.FLAG_SYSTEM == 0) ||
                    (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)
                val packageName = app.packageName?.trim().orEmpty()
                if (packageName.isBlank() || packageName == context.packageName || !isUserApp) {
                    return@mapNotNull null
                }
                InstalledApp(
                    packageName = packageName,
                    label = pm.getApplicationLabel(app).toString()
                )
            }
            .distinctBy { app -> app.packageName }
            .sortedWith(
                compareBy<InstalledApp> { app -> app.label.lowercase() }
                    .thenBy { app -> app.packageName }
            )
            .toList()
            .ifEmpty {
                pm.getInstalledApplications(0)
                    .asSequence()
                    .filter { app ->
                        val isUserApp = (app.flags and ApplicationInfo.FLAG_SYSTEM == 0) ||
                            (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)
                        app.packageName != context.packageName &&
                            pm.getLaunchIntentForPackage(app.packageName) != null &&
                            isUserApp
                    }
                    .map { app ->
                        InstalledApp(
                            packageName = app.packageName,
                            label = pm.getApplicationLabel(app).toString()
                        )
                    }
                    .sortedBy { app -> app.label.lowercase() }
                    .toList()
            }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
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

    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }

        return mode == AppOpsManager.MODE_ALLOWED
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

    private suspend fun resolveTokenAccessWithDomainPool(
        token: String,
        deviceData: DeviceRegistrationData
    ): DomainResolvedAccess {
        val prefs = preferencesRepository.preferences.first()
        val candidates = preferencesRepository.apiBaseUrlCandidates(prefs)
        require(candidates.isNotEmpty()) { INVALID_TOKEN_MESSAGE }
        Log.i(TAG, "Resolving access via ${candidates.size} API domain candidate(s)")

        var lastRecoverableError: Throwable? = null
        for ((index, baseUrl) in candidates.withIndex()) {
            Log.i(TAG, "Trying API domain ${index + 1}/${candidates.size}: $baseUrl")
            val result = orchestratorClient.resolveTokenAccess(
                baseUrl = baseUrl,
                token = token,
                deviceData = deviceData
            )
            val access = result.getOrNull()
            if (access != null) {
                Log.i(
                    TAG,
                    "API domain accepted: $baseUrl, domain rev=${access.domainBundle.revision ?: "none"}, api=${access.domainBundle.api.size}, web=${access.domainBundle.web.size}"
                )
                return DomainResolvedAccess(access = access, apiBaseUrl = baseUrl)
            }

            val error = result.exceptionOrNull() ?: continue
            if (!shouldTryNextDomain(error)) {
                Log.w(TAG, "API domain failed with terminal access error: $baseUrl (${error.javaClass.simpleName})")
                throw error
            }
            Log.w(TAG, "API domain failed, trying next if available: $baseUrl (${error.javaClass.simpleName}: ${error.message})")
            lastRecoverableError = error
        }

        throw lastRecoverableError ?: InvalidTokenException(INVALID_TOKEN_MESSAGE)
    }

    private suspend fun rememberSuccessfulDomain(resolved: DomainResolvedAccess) {
        preferencesRepository.setLastWorkingApiBaseUrl(resolved.apiBaseUrl)
        preferencesRepository.rememberDomainBundle(resolved.access.domainBundle)
        Log.i(
            TAG,
            "Remembered API domain ${resolved.apiBaseUrl}, domain rev=${resolved.access.domainBundle.revision ?: "none"}"
        )
    }

    private fun shouldTryNextDomain(error: Throwable): Boolean {
        return when (error) {
            is InvalidTokenException,
            is NoActiveAccessException,
            is AccessNotReadyException -> false
            is TokenAuthRequestException -> {
                val statusCode = error.statusCode
                statusCode == null || statusCode == 408 || statusCode == 429 || statusCode >= 500
            }
            is IOException -> true
            is IllegalArgumentException -> true
            else -> true
        }
    }

    private suspend fun finalizeTokenExchange(
        access: TokenAccessResponse
    ): ResolvedNodeAccess {
        val deviceToken = access.deviceToken
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: throw InvalidTokenException(INVALID_TOKEN_MESSAGE)
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
            throw NoActiveAccessException("Нет активного доступа для этого устройства")
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
        const val TAG = "ZnetAccess"
        const val REQUIRED_TOKEN_LENGTH = 32
        const val INVALID_TOKEN_MESSAGE = "Некорректный токен"
        const val ACCESS_NOT_READY_MESSAGE = "Доступ пока не готов"
    }
}

private data class DomainResolvedAccess(
    val access: TokenAccessResponse,
    val apiBaseUrl: String
)
