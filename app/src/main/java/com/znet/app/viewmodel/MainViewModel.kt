package com.znet.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.znet.app.AppContainer
import com.znet.app.BuildConfig
import com.znet.app.data.UserPreferences
import com.znet.app.data.UserPreferencesRepository
import com.znet.app.data.model.ConnectionState
import com.znet.app.data.model.InstalledApp
import com.znet.app.data.model.ServerNode
import com.znet.app.data.repo.ResolvedNodeAccess
import com.znet.app.data.repo.VpnRepository
import com.znet.app.vpn.VpnStatus
import com.znet.app.vpn.VpnStatusBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MainUiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val nodes: List<ServerNode> = emptyList(),
    val selectedNodeId: String? = null,
    val selectedNode: ServerNode? = null,
    val installedApps: List<InstalledApp> = emptyList(),
    val splitTunnelApps: Set<String> = emptySet(),
    val autoDisconnectApps: Set<String> = emptySet(),
    val adaptiveEnabled: Boolean = true,
    val latencyMs: Long = -1,
    val rxBytes: Long = 0,
    val txBytes: Long = 0,
    val manualVlessLink: String = "",
    val deviceToken: String = "",
    val hasActiveAccess: String = "",
    val activeSubscriptionLinks: String = "",
    val filteredSubscriptionLinks: String = "",
    val selectedSubscriptionLink: String = "",
    val isAuthenticated: Boolean = false,
    val authTokenInput: String = "",
    val authError: String? = null,
    val debugApiResponse: String? = null,
    val isDebugMode: Boolean = false,
    val authInProgress: Boolean = false,
    val pendingAutoConnect: Boolean = false,
    val isBusy: Boolean = false,
    val message: String? = null
)

class MainViewModel(
    application: Application,
    private val preferencesRepository: UserPreferencesRepository,
    private val vpnRepository: VpnRepository
) : AndroidViewModel(application) {

    private val loadedNodes = MutableStateFlow<List<ServerNode>>(emptyList())
    private val installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val isAuthenticated = MutableStateFlow(false)

    private val authTokenInput = MutableStateFlow("")
    private val authError = MutableStateFlow<String?>(null)
    private val debugApiResponse = MutableStateFlow<String?>(null)

    private val authInProgress = MutableStateFlow(false)
    private val pendingAutoConnect = MutableStateFlow(false)
    private val resolvedAccess = MutableStateFlow<ResolvedNodeAccess?>(null)

    private val baseUiState = combine(
        preferencesRepository.preferences,
        VpnStatusBus.status,
        loadedNodes,
        installedApps,
        busy,
        isAuthenticated,
        authTokenInput,
        authError,
        debugApiResponse,
        authInProgress,
        pendingAutoConnect
    ) { values ->
        val prefs = values[0] as UserPreferences
        val status = values[1] as VpnStatus
        val nodes = values[2] as List<ServerNode>
        val apps = values[3] as List<InstalledApp>
        val isBusyValue = values[4] as Boolean
        val authenticated = values[5] as Boolean
        val tokenInput = values[6] as String
        val currentAuthError = values[7] as String?
        val currentDebugApiResponse = values[8] as String?
        val authorizing = values[9] as Boolean
        val shouldAutoConnect = values[10] as Boolean

        val selectedNode = nodes.firstOrNull { it.id == prefs.selectedNodeId } ?: status.currentNode
        MainUiState(
            connectionState = status.state,
            nodes = nodes,
            selectedNodeId = prefs.selectedNodeId,
            selectedNode = selectedNode,
            installedApps = apps,
            splitTunnelApps = prefs.splitTunnelApps,
            autoDisconnectApps = prefs.autoDisconnectApps,
            adaptiveEnabled = prefs.adaptiveEnabled,
            latencyMs = status.latencyMs,
            rxBytes = status.rxBytes,
            txBytes = status.txBytes,
            manualVlessLink = prefs.manualVlessLink,
            deviceToken = prefs.deviceToken,
            hasActiveAccess = prefs.hasActiveAccess,
            activeSubscriptionLinks = prefs.activeSubscriptionLinks,
            filteredSubscriptionLinks = prefs.filteredSubscriptionLinks,
            selectedSubscriptionLink = prefs.selectedSubscriptionLink,
            isAuthenticated = authenticated,
            authTokenInput = tokenInput,
            authError = currentAuthError,
            debugApiResponse = currentDebugApiResponse,
            isDebugMode = BuildConfig.DEBUG,
            authInProgress = authorizing,
            pendingAutoConnect = shouldAutoConnect,
            isBusy = isBusyValue,
            message = status.errorMessage
        )
    }

    val uiState: StateFlow<MainUiState> = combine(baseUiState, message) { base, uiMessage ->
        base.copy(message = uiMessage ?: base.message)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState()
    )

    init {
        loadInstalledApps()
        viewModelScope.launch {
            val prefs = preferencesRepository.preferences.first()
            authTokenInput.value = prefs.authToken.ifBlank { prefs.deviceToken }
        }
    }

    fun refreshNodes() {
        if (!isAuthenticated.value) {
            message.value = "Sign in first"
            return
        }
        viewModelScope.launch {
            busy.value = true
            val result = vpnRepository.refreshAccessBundle()
            result.fold(
                onSuccess = { access ->
                    resolvedAccess.value = access
                    loadedNodes.value = listOf(access.node)
                    preferencesRepository.setSelectedNode(access.node.id)
                    message.value = null
                },
                onFailure = { err ->
                    message.value = err.message ?: "Failed to refresh access"
                }
            )
            busy.value = false
        }
    }

    fun loadInstalledApps() {
        viewModelScope.launch {
            installedApps.value = vpnRepository.loadInstalledApps()
        }
    }

    fun updateAuthTokenInput(value: String) {
        authTokenInput.value = value
        authError.value = null
        if (BuildConfig.DEBUG) {
            debugApiResponse.value = null
        }
    }

    fun submitAuth() {
        viewModelScope.launch {
            submitTokenAuth()
        }
    }

    private suspend fun submitTokenAuth() {
        val token = authTokenInput.value.trim()
        if (token.isBlank() || token.length != REQUIRED_TOKEN_LENGTH) {
            authError.value = INVALID_TOKEN_MESSAGE
            debugApiResponse.value = null
            return
        }

        authError.value = null
        authInProgress.value = true

        if (BuildConfig.DEBUG) {
            val debugResult = vpnRepository.fetchTokenDebugResponse(token)
            debugResult.fold(
                onSuccess = { raw ->
                    debugApiResponse.value = raw.ifBlank { "<empty response>" }
                    authError.value = null
                },
                onFailure = { err ->
                    debugApiResponse.value = err.message ?: "request failed"
                    authError.value = INVALID_TOKEN_MESSAGE
                }
            )
        }

        val result = vpnRepository.authenticateWithToken(token = token)
        result.fold(
            onSuccess = { access ->
                resolvedAccess.value = access
                loadedNodes.value = listOf(access.node)
                preferencesRepository.setSelectedNode(access.node.id)
                isAuthenticated.value = true
                pendingAutoConnect.value = true
                authError.value = null
                debugApiResponse.value = null
                message.value = null
            },
            onFailure = {
                resolvedAccess.value = null
                if (BuildConfig.DEBUG) {
                    // In debug mode we allow entering the main screen right after token input.
                    isAuthenticated.value = true
                    pendingAutoConnect.value = false
                    authError.value = null
                } else {
                    isAuthenticated.value = false
                    pendingAutoConnect.value = false
                    authError.value = INVALID_TOKEN_MESSAGE
                }
            }
        )
        authInProgress.value = false
    }

    fun consumeAutoConnectRequest() {
        pendingAutoConnect.value = false
    }

    fun saveManualVlessLink(link: String) {
        viewModelScope.launch {
            preferencesRepository.setManualVlessLink(link.trim())
            message.value = "VLESS link saved"
        }
    }

    fun selectNode(nodeId: String) {
        viewModelScope.launch {
            preferencesRepository.setSelectedNode(nodeId)
            if (resolvedAccess.value?.node?.id != nodeId) {
                resolvedAccess.value = null
            }
        }
    }

    fun toggleAdaptive(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setAdaptiveEnabled(enabled)
        }
    }

    fun toggleSplitTunnel(packageName: String) {
        viewModelScope.launch {
            val prefs = preferencesRepository.preferences.first()
            val updated = prefs.splitTunnelApps.toMutableSet().apply {
                if (!add(packageName)) remove(packageName)
            }
            preferencesRepository.setSplitTunnelApps(updated)
        }
    }

    fun toggleAutoDisconnect(packageName: String) {
        viewModelScope.launch {
            val prefs = preferencesRepository.preferences.first()
            val updated = prefs.autoDisconnectApps.toMutableSet().apply {
                if (!add(packageName)) remove(packageName)
            }
            preferencesRepository.setAutoDisconnectApps(updated)
        }
    }

    fun connect() {
        viewModelScope.launch {
            val prefs: UserPreferences = preferencesRepository.preferences.first()
            val manualVlessLink = prefs.manualVlessLink.trim()
            val linkedFromAuth = prefs.selectedSubscriptionLink.trim()
            val linkedFromFiltered = prefs.filteredSubscriptionLinks
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("vless://", ignoreCase = true) }
                .orEmpty()
            val linkedFromActive = prefs.activeSubscriptionLinks
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("vless://", ignoreCase = true) }
                .orEmpty()
            val preferredVlessLink = when {
                manualVlessLink.isNotBlank() -> manualVlessLink
                linkedFromAuth.startsWith("vless://", ignoreCase = true) -> linkedFromAuth
                linkedFromFiltered.isNotBlank() -> linkedFromFiltered
                linkedFromActive.isNotBlank() -> linkedFromActive
                else -> ""
            }
            if (preferredVlessLink.isNotBlank()) {
                busy.value = true
                val resolved = vpnRepository.resolveManualVlessLink(preferredVlessLink)
                resolved.fold(
                    onSuccess = { access ->
                        resolvedAccess.value = access
                        loadedNodes.value = listOf(access.node)
                        preferencesRepository.setSelectedNode(access.node.id)
                        vpnRepository.startVpn(
                            node = access.node,
                            xrayConfig = access.xrayConfig,
                            splitTunnelApps = prefs.splitTunnelApps,
                            autoDisconnectApps = prefs.autoDisconnectApps,
                            latencyMs = -1L
                        )
                        message.value = null
                    },
                    onFailure = {
                        message.value = "Invalid VLESS link"
                    }
                )
                busy.value = false
                return@launch
            }

            if (!isAuthenticated.value) {
                message.value = "Sign in first"
                return@launch
            }

            busy.value = true
            val accessResult = resolvedAccess.value?.let { Result.success(it) } ?: vpnRepository.refreshAccessBundle()
            accessResult.fold(
                onSuccess = { access ->
                    resolvedAccess.value = access
                    loadedNodes.value = listOf(access.node)
                    preferencesRepository.setSelectedNode(access.node.id)
                    vpnRepository.startVpn(
                        node = access.node,
                        xrayConfig = access.xrayConfig,
                        splitTunnelApps = prefs.splitTunnelApps,
                        autoDisconnectApps = prefs.autoDisconnectApps,
                        latencyMs = -1L
                    )
                    message.value = null
                },
                onFailure = { err ->
                    message.value = err.message ?: "Unable to load VPN access"
                }
            )
            busy.value = false
        }
    }

    fun disconnect() {
        vpnRepository.stopVpn()
    }

    fun clearMessage() {
        message.value = null
    }

    class Factory(
        private val application: Application,
        private val container: AppContainer
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(
                application = application,
                preferencesRepository = container.preferencesRepository,
                vpnRepository = container.vpnRepository
            ) as T
        }
    }

    private companion object {
        const val REQUIRED_TOKEN_LENGTH = 32
        const val INVALID_TOKEN_MESSAGE = "некорретный токен"
    }
}
