package com.znet.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.znet.app.AppContainer
import com.znet.app.data.UserPreferences
import com.znet.app.data.UserPreferencesRepository
import com.znet.app.data.model.ConnectionState
import com.znet.app.data.model.InstalledApp
import com.znet.app.data.model.ServerNode
import com.znet.app.data.remote.AccessNotReadyException
import com.znet.app.data.remote.InvalidTokenException
import com.znet.app.data.remote.NoActiveAccessException
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
    val isAuthenticated: Boolean = false,
    val authTokenInput: String = "",
    val authError: String? = null,
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
        val authorizing = values[8] as Boolean
        val shouldAutoConnect = values[9] as Boolean

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
            isAuthenticated = authenticated,
            authTokenInput = tokenInput,
            authError = currentAuthError,
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
            val persistedToken = prefs.deviceToken.ifBlank { prefs.authToken }
            authTokenInput.value = persistedToken

            if (persistedToken.length == REQUIRED_TOKEN_LENGTH) {
                authInProgress.value = true
                val restore = vpnRepository.refreshAccessBundle()
                restore.fold(
                    onSuccess = { access ->
                        resolvedAccess.value = access
                        loadedNodes.value = listOf(access.node)
                        preferencesRepository.setSelectedNode(access.node.id)
                        isAuthenticated.value = true
                        authError.value = null
                        message.value = null
                    },
                    onFailure = { error ->
                        handleStartupRestoreFailure(
                            persistedToken = persistedToken,
                            error = error
                        )
                    }
                )
                authInProgress.value = false
            }
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
                    if (isInvalidSessionFailure(err)) {
                        invalidateSession(err)
                    } else {
                        message.value = err.message ?: "Failed to refresh access"
                    }
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
            return
        }

        authError.value = null
        authInProgress.value = true

        val result = vpnRepository.authenticateWithToken(token = token)
        result.fold(
            onSuccess = { access ->
                resolvedAccess.value = access
                loadedNodes.value = listOf(access.node)
                preferencesRepository.setSelectedNode(access.node.id)
                isAuthenticated.value = true
                pendingAutoConnect.value = true
                authError.value = null
                authTokenInput.value = currentSessionToken()
                message.value = null
            },
            onFailure = { error ->
                val restored = tryRestoreSessionAfterExchange(
                    consumedToken = token,
                    initialError = error
                )
                if (!restored) {
                    handleUnauthenticatedFailure(error)
                }
            }
        )
        authInProgress.value = false
    }

    private suspend fun tryRestoreSessionAfterExchange(
        consumedToken: String,
        initialError: Throwable
    ): Boolean {
        val persistedToken = currentSessionToken()
        if (
            persistedToken.isBlank() ||
            persistedToken == consumedToken ||
            persistedToken.length != REQUIRED_TOKEN_LENGTH
        ) {
            return false
        }

        if (initialError is AccessNotReadyException) {
            resolvedAccess.value = null
            loadedNodes.value = emptyList()
            isAuthenticated.value = true
            pendingAutoConnect.value = false
            authError.value = null
            authTokenInput.value = persistedToken
            message.value = initialError.message ?: ACCESS_NOT_READY_MESSAGE
            return true
        }

        val restored = vpnRepository.refreshAccessBundle()
        restored.fold(
            onSuccess = { access ->
                resolvedAccess.value = access
                loadedNodes.value = listOf(access.node)
                preferencesRepository.setSelectedNode(access.node.id)
                isAuthenticated.value = true
                pendingAutoConnect.value = true
                authError.value = null
                authTokenInput.value = persistedToken
                message.value = null
            },
            onFailure = { error ->
                if (isInvalidSessionFailure(error)) {
                    invalidateSession(error)
                } else {
                    resolvedAccess.value = null
                    loadedNodes.value = emptyList()
                    isAuthenticated.value = true
                    pendingAutoConnect.value = false
                    authError.value = null
                    authTokenInput.value = persistedToken
                    message.value = error.message ?: ACCESS_NOT_READY_MESSAGE
                }
            }
        )

        return restored.isSuccess || !isInvalidSessionFailure(restored.exceptionOrNull() ?: return false)
    }

    private suspend fun currentSessionToken(): String {
        val prefs = preferencesRepository.preferences.first()
        return prefs.deviceToken.ifBlank { prefs.authToken }.trim()
    }

    fun consumeAutoConnectRequest() {
        pendingAutoConnect.value = false
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
            if (!isAuthenticated.value) {
                val persistedToken = prefs.deviceToken.ifBlank { prefs.authToken }
                if (persistedToken.length != REQUIRED_TOKEN_LENGTH) {
                    message.value = "Sign in first"
                    return@launch
                }
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
                    if (isInvalidSessionFailure(err)) {
                        invalidateSession(err)
                    } else {
                        message.value = err.message ?: "Unable to load VPN access"
                    }
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

    private suspend fun handleStartupRestoreFailure(
        persistedToken: String,
        error: Throwable
    ) {
        if (isInvalidSessionFailure(error)) {
            invalidateSession(error, clearMessage = true)
            return
        }

        resolvedAccess.value = null
        loadedNodes.value = emptyList()
        isAuthenticated.value = persistedToken.length == REQUIRED_TOKEN_LENGTH
        authError.value = null
        message.value = error.message ?: ACCESS_NOT_READY_MESSAGE
    }

    private fun handleUnauthenticatedFailure(error: Throwable) {
        resolvedAccess.value = null
        loadedNodes.value = emptyList()
        isAuthenticated.value = false
        pendingAutoConnect.value = false
        authError.value = error.message ?: INVALID_TOKEN_MESSAGE
        message.value = null
    }

    private suspend fun invalidateSession(
        error: Throwable,
        clearMessage: Boolean = false
    ) {
        vpnRepository.clearSession()
        resolvedAccess.value = null
        loadedNodes.value = emptyList()
        isAuthenticated.value = false
        pendingAutoConnect.value = false
        authError.value = error.message ?: INVALID_TOKEN_MESSAGE
        authTokenInput.value = ""
        message.value = if (clearMessage) null else message.value
    }

    private fun isInvalidSessionFailure(error: Throwable): Boolean {
        return error is InvalidTokenException || error is NoActiveAccessException
    }

    private companion object {
        const val REQUIRED_TOKEN_LENGTH = 32
        const val INVALID_TOKEN_MESSAGE = "некорректный токен"
        const val ACCESS_NOT_READY_MESSAGE = "доступ еще не готов"
    }
}
