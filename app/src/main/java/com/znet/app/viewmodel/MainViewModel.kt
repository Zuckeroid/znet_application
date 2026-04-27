package com.znet.app.viewmodel

import android.app.Application
import android.util.Log
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
    val protocol: String? = null,
    val serviceTitle: String? = null,
    val serviceExpiresAt: String? = null,
    val serviceDaysRemaining: Int? = null,
    val isAuthenticated: Boolean = false,
    val sessionRestoreInProgress: Boolean = false,
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
    private val sessionRestoreInProgress = MutableStateFlow(false)

    private val authTokenInput = MutableStateFlow("")
    private val authError = MutableStateFlow<String?>(null)

    private val authInProgress = MutableStateFlow(false)
    private val pendingAutoConnect = MutableStateFlow(false)
    private val resolvedAccess = MutableStateFlow<ResolvedNodeAccess?>(null)
    private val sessionValidationInProgress = MutableStateFlow(false)

    private val baseUiState = combine(
        preferencesRepository.preferences,
        VpnStatusBus.status,
        loadedNodes,
        installedApps,
        busy,
        isAuthenticated,
        sessionRestoreInProgress,
        authTokenInput,
        authError,
        authInProgress,
        pendingAutoConnect,
        resolvedAccess
    ) { values ->
        val prefs = values[0] as UserPreferences
        val status = values[1] as VpnStatus
        val nodes = values[2] as List<ServerNode>
        val apps = values[3] as List<InstalledApp>
        val isBusyValue = values[4] as Boolean
        val authenticated = values[5] as Boolean
        val restoringSession = values[6] as Boolean
        val tokenInput = values[7] as String
        val currentAuthError = values[8] as String?
        val authorizing = values[9] as Boolean
        val shouldAutoConnect = values[10] as Boolean
        val access = values[11] as ResolvedNodeAccess?

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
            protocol = access?.protocol,
            serviceTitle = access?.serviceTitle,
            serviceExpiresAt = access?.serviceExpiresAt,
            serviceDaysRemaining = access?.serviceDaysRemaining,
            isAuthenticated = authenticated,
            sessionRestoreInProgress = restoringSession,
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
            preferencesRepository.migrateLegacySessionTokenIfNeeded()
            restorePersistedSession()
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
                    applyAuthenticatedAccess(
                        access = access,
                        autoConnect = false
                    )
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

    fun validateDeviceSessionOnForeground() {
        viewModelScope.launch {
            if (sessionRestoreInProgress.value || sessionValidationInProgress.value || authInProgress.value) {
                return@launch
            }

            val sessionToken = currentSessionToken()
            if (sessionToken.length != REQUIRED_TOKEN_LENGTH) {
                if (isAuthenticated.value) {
                    invalidateSession(InvalidTokenException(INVALID_TOKEN_MESSAGE), clearMessage = true)
                }
                return@launch
            }

            sessionValidationInProgress.value = true
            Log.d(TAG, "Validating persisted device session on foreground")
            val validation = vpnRepository.refreshAccessBundle()
            validation.fold(
                onSuccess = { access ->
                    applyAuthenticatedAccess(
                        access = access,
                        autoConnect = false
                    )
                },
                onFailure = { error ->
                    handleForegroundValidationFailure(error)
                }
            )
            sessionValidationInProgress.value = false
        }
    }

    private suspend fun restorePersistedSession() {
        val persistedToken = currentSessionToken()
        if (persistedToken.length != REQUIRED_TOKEN_LENGTH) {
            authTokenInput.value = ""
            return
        }

        sessionRestoreInProgress.value = true
        val restore = vpnRepository.refreshAccessBundle()
        restore.fold(
            onSuccess = { access ->
                applyAuthenticatedAccess(
                    access = access,
                    autoConnect = false
                )
            },
            onFailure = { error ->
                handleStartupRestoreFailure(error)
            }
        )
        sessionRestoreInProgress.value = false
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
                applyAuthenticatedAccess(
                    access = access,
                    autoConnect = true
                )
                authTokenInput.value = ""
            },
            onFailure = { error ->
                handleAuthenticationFailure(error)
            }
        )
        authInProgress.value = false
    }

    private suspend fun currentSessionToken(): String {
        val prefs = preferencesRepository.preferences.first()
        return prefs.deviceToken.trim()
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
            if (!isAuthenticated.value && prefs.deviceToken.length != REQUIRED_TOKEN_LENGTH) {
                message.value = "Sign in first"
                return@launch
            }

            busy.value = true
            val accessResult = resolvedAccess.value?.let { Result.success(it) } ?: vpnRepository.refreshAccessBundle()
            accessResult.fold(
                onSuccess = { access ->
                    applyAuthenticatedAccess(
                        access = access,
                        autoConnect = false
                    )
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
        error: Throwable
    ) {
        if (isInvalidSessionFailure(error)) {
            invalidateSession(error, clearMessage = true)
            return
        }

        enterAuthenticatedPendingState(error)
    }

    private suspend fun handleForegroundValidationFailure(error: Throwable) {
        if (isInvalidSessionFailure(error)) {
            invalidateSession(error, clearMessage = true)
            return
        }

        if (error is AccessNotReadyException && !isAuthenticated.value) {
            invalidateSession(error, clearMessage = true)
            return
        }

        if (error is AccessNotReadyException) {
            enterAuthenticatedPendingState(error)
            return
        }

        message.value = error.message ?: "Failed to refresh access"
    }

    private suspend fun handleAuthenticationFailure(error: Throwable) {
        val sessionToken = currentSessionToken()
        if (error is AccessNotReadyException && sessionToken.length == REQUIRED_TOKEN_LENGTH) {
            enterAuthenticatedPendingState(error)
            authTokenInput.value = ""
            return
        }

        handleUnauthenticatedFailure(error)
    }

    private suspend fun applyAuthenticatedAccess(
        access: ResolvedNodeAccess,
        autoConnect: Boolean
    ) {
        resolvedAccess.value = access
        loadedNodes.value = listOf(access.node)
        preferencesRepository.setSelectedNode(access.node.id)
        isAuthenticated.value = true
        pendingAutoConnect.value = autoConnect
        authError.value = null
        message.value = null
    }

    private fun enterAuthenticatedPendingState(error: Throwable) {
        resolvedAccess.value = null
        loadedNodes.value = emptyList()
        isAuthenticated.value = true
        pendingAutoConnect.value = false
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
        vpnRepository.stopVpn()
        vpnRepository.clearSession()
        resolvedAccess.value = null
        loadedNodes.value = emptyList()
        isAuthenticated.value = false
        sessionRestoreInProgress.value = false
        pendingAutoConnect.value = false
        authError.value = error.message ?: INVALID_TOKEN_MESSAGE
        authTokenInput.value = ""
        message.value = if (clearMessage) null else message.value
    }

    private fun isInvalidSessionFailure(error: Throwable): Boolean {
        return error is InvalidTokenException || error is NoActiveAccessException
    }

    private companion object {
        const val TAG = "MainViewModel"
        const val REQUIRED_TOKEN_LENGTH = 32
        const val INVALID_TOKEN_MESSAGE = "Invalid token"
        const val ACCESS_NOT_READY_MESSAGE = "Access is not ready yet"
    }
}
