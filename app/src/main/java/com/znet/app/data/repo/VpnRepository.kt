package com.znet.app.data.repo

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import com.znet.app.BuildConfig
import com.znet.app.data.UserPreferencesRepository
import com.znet.app.data.model.InstalledApp
import com.znet.app.data.model.ServerNode
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
    private val adaptiveNodeSelector: AdaptiveNodeSelector,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    fun resolveManualVlessLink(link: String): Result<ResolvedNodeAccess> {
        return runCatching {
            val cleanLink = link.trim()
            require(cleanLink.isNotBlank()) { "VLESS link is empty" }
            require(cleanLink.startsWith("vless://", ignoreCase = true)) { "Invalid VLESS link" }
            NodeLinkConfigFactory.fromTokenAccess(
                TokenAccessResponse(
                    nodeLink = cleanLink,
                    xrayConfig = null,
                    nodeId = null,
                    nodeName = "Manual VLESS",
                    country = "Custom",
                    city = "User link",
                    flagEmoji = "GL",
                    issuedToken = null,
                    orchestratorBaseUrl = null
                )
            )
        }
    }

    suspend fun fetchTokenDebugResponse(token: String): Result<String> {
        return runCatching {
            val cleanToken = token.trim()
            val authBaseUrl = BuildConfig.AUTH_API_URL.trimEnd('/')

            require(cleanToken.isNotBlank()) { INVALID_TOKEN_MESSAGE }
            require(cleanToken.length == REQUIRED_TOKEN_LENGTH) { INVALID_TOKEN_MESSAGE }
            require(authBaseUrl.isNotBlank()) { INVALID_TOKEN_MESSAGE }

            orchestratorClient.requestTokenAuthRaw(
                baseUrl = authBaseUrl,
                token = cleanToken
            ).getOrThrow()
        }
    }

    suspend fun authenticateWithToken(token: String): Result<ResolvedNodeAccess> {
        return runCatching {
            val cleanToken = token.trim()
            val authBaseUrl = BuildConfig.AUTH_API_URL.trimEnd('/')

            require(cleanToken.isNotBlank()) { INVALID_TOKEN_MESSAGE }
            require(cleanToken.length == REQUIRED_TOKEN_LENGTH) { INVALID_TOKEN_MESSAGE }
            require(authBaseUrl.isNotBlank()) { INVALID_TOKEN_MESSAGE }

            val deviceId = ensureDeviceId()
            val access = orchestratorClient.resolveTokenAccess(
                baseUrl = authBaseUrl,
                token = cleanToken
            ).getOrThrow()

            finalizeAuthentication(
                access = access,
                authBaseUrl = authBaseUrl,
                fallbackToken = cleanToken,
                deviceId = deviceId
            )
        }
    }

    suspend fun fetchNodes(): Result<List<ServerNode>> {
        val prefs = preferencesRepository.preferences.first()
        return orchestratorClient.fetchNodes(prefs.orchestratorBaseUrl, prefs.authToken)
    }

    suspend fun chooseNode(nodes: List<ServerNode>): Pair<ServerNode, Long> {
        val prefs = preferencesRepository.preferences.first()
        return adaptiveNodeSelector.chooseNode(nodes, prefs.adaptiveEnabled)
    }

    suspend fun fetchXrayConfig(nodeId: String): Result<String> {
        val prefs = preferencesRepository.preferences.first()
        val deviceId = ensureDeviceId()
        return orchestratorClient.fetchXrayConfig(
            baseUrl = prefs.orchestratorBaseUrl,
            token = prefs.authToken,
            nodeId = nodeId,
            deviceId = deviceId
        )
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

    private suspend fun ensureDeviceId(): String {
        return preferencesRepository.getOrCreateDeviceId()
    }

    private suspend fun finalizeAuthentication(
        access: TokenAccessResponse,
        authBaseUrl: String,
        fallbackToken: String,
        deviceId: String
    ): ResolvedNodeAccess {
        val resolvedBaseUrl = access.orchestratorBaseUrl
            ?.trimEnd('/')
            ?.takeIf { it.isNotBlank() }
            ?: authBaseUrl
        val sessionToken = access.issuedToken
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: fallbackToken

        preferencesRepository.setOrchestrator(resolvedBaseUrl, sessionToken)
        preferencesRepository.setTokenAuthMetadata(
            deviceToken = access.deviceToken,
            hasActiveAccess = access.hasActiveAccess,
            activeSubscriptionLinks = access.activeSubscriptionLinks,
            filteredSubscriptionLinks = access.filteredSubscriptionLinks,
            selectedSubscriptionLink = access.selectedSubscriptionLink
        )
        return resolveNodeAccess(
            access = access,
            baseUrl = resolvedBaseUrl,
            token = sessionToken,
            deviceId = deviceId
        )
    }

    private suspend fun resolveNodeAccess(
        access: TokenAccessResponse,
        baseUrl: String,
        token: String,
        deviceId: String
    ): ResolvedNodeAccess {
        val nodeId = access.nodeId?.takeIf { it.isNotBlank() }
        if (nodeId != null && token.isNotBlank()) {
            val configResult = orchestratorClient.fetchXrayConfig(
                baseUrl = baseUrl,
                token = token,
                nodeId = nodeId,
                deviceId = deviceId
            )
            val xrayConfig = configResult.getOrNull()
            if (!xrayConfig.isNullOrBlank()) {
                return NodeLinkConfigFactory.fromTokenAccess(
                    access.copy(xrayConfig = xrayConfig)
                )
            }
        }

        return NodeLinkConfigFactory.fromTokenAccess(access)
    }

    private companion object {
        const val REQUIRED_TOKEN_LENGTH = 32
        const val INVALID_TOKEN_MESSAGE = "некорретный токен"
    }
}
