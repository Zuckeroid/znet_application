package com.znet.app.viewmodel

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import com.znet.app.data.remote.AppAutomationPolicy
import com.znet.app.data.remote.AppRoutingPolicy
import com.znet.app.data.remote.AccessNotReadyException
import com.znet.app.data.remote.InvalidTokenException
import com.znet.app.data.remote.NoActiveAccessException
import com.znet.app.data.repo.ResolvedNodeAccess
import com.znet.app.data.repo.ResolvedAccessProfile
import com.znet.app.data.repo.VpnRepository
import com.znet.app.vpn.VpnStatus
import com.znet.app.vpn.VpnStatusBus
import kotlinx.coroutines.delay
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
    val routingApps: Set<String> = emptySet(),
    val autoConnectApps: Set<String> = emptySet(),
    val autoDisconnectApps: Set<String> = emptySet(),
    val routingEnabled: Boolean = true,
    val autoConnectEnabled: Boolean = true,
    val autoDisconnectEnabled: Boolean = true,
    val awayModeEnabled: Boolean = false,
    val awayModeAvailable: Boolean = false,
    val routingPolicy: AppRoutingPolicy = AppRoutingPolicy(),
    val automationPolicy: AppAutomationPolicy = AppAutomationPolicy(),
    val adaptiveEnabled: Boolean = true,
    val latencyMs: Long = -1,
    val protocol: String? = null,
    val serviceTitle: String? = null,
    val serviceExpiresAt: String? = null,
    val serviceDaysRemaining: Int? = null,
    val webBaseUrl: String = "https://my-storage.org",
    val isAuthenticated: Boolean = false,
    val sessionRestoreInProgress: Boolean = false,
    val authTokenInput: String = "",
    val authError: String? = null,
    val authInProgress: Boolean = false,
    val pendingAutoConnect: Boolean = false,
    val vpnTransportActive: Boolean = false,
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
    private val sessionRestoreInProgress = MutableStateFlow(true)

    private val authTokenInput = MutableStateFlow("")
    private val authError = MutableStateFlow<String?>(null)

    private val authInProgress = MutableStateFlow(false)
    private val pendingAutoConnect = MutableStateFlow(false)
    private val vpnTransportActive = MutableStateFlow(false)
    private val resolvedAccess = MutableStateFlow<ResolvedNodeAccess?>(null)
    private val sessionValidationInProgress = MutableStateFlow(false)
    private var usageAccessPromptShown = false
    private val connectivityManager =
        application.getSystemService(Application.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var vpnNetworkCallback: ConnectivityManager.NetworkCallback? = null

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
        resolvedAccess,
        vpnTransportActive
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
        val hasVpnTransport = values[12] as Boolean

        val activeProfile = access?.let { activeProfile(it, prefs) }
        val selectedNode = activeProfile?.node
            ?: nodes.firstOrNull { it.id == prefs.selectedNodeId }
            ?: status.currentNode
        MainUiState(
            connectionState = status.state,
            nodes = nodes,
            selectedNodeId = prefs.selectedNodeId,
            selectedNode = selectedNode,
            installedApps = apps,
            routingApps = prefs.routingApps,
            autoConnectApps = prefs.autoConnectApps,
            autoDisconnectApps = prefs.autoDisconnectApps,
            routingEnabled = prefs.routingEnabled,
            autoConnectEnabled = prefs.autoConnectEnabled,
            autoDisconnectEnabled = prefs.autoDisconnectEnabled,
            awayModeEnabled = prefs.awayModeEnabled,
            awayModeAvailable = access?.profiles?.containsKey(AWAY_PROFILE_ID) == true,
            routingPolicy = activeProfile?.routingPolicy ?: access?.routingPolicy ?: AppRoutingPolicy(),
            automationPolicy = activeProfile?.automationPolicy ?: access?.automationPolicy ?: AppAutomationPolicy(),
            adaptiveEnabled = prefs.adaptiveEnabled,
            latencyMs = status.latencyMs,
            protocol = activeProfile?.protocol ?: access?.protocol,
            serviceTitle = access?.serviceTitle,
            serviceExpiresAt = access?.serviceExpiresAt,
            serviceDaysRemaining = access?.serviceDaysRemaining,
            webBaseUrl = prefs.webBaseUrl,
            isAuthenticated = authenticated,
            sessionRestoreInProgress = restoringSession,
            authTokenInput = tokenInput,
            authError = currentAuthError,
            authInProgress = authorizing,
            pendingAutoConnect = shouldAutoConnect,
            vpnTransportActive = hasVpnTransport,
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
        startVpnEnvironmentMonitor()
        viewModelScope.launch {
            preferencesRepository.migrateLegacySessionTokenIfNeeded()
            restorePersistedSession()
        }
    }

    override fun onCleared() {
        vpnNetworkCallback?.let { callback ->
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
        vpnNetworkCallback = null
        super.onCleared()
    }

    fun refreshNodes() {
        if (!isAuthenticated.value) {
            message.value = "Сначала войдите по токену"
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
            sessionRestoreInProgress.value = false
            return
        }

        sessionRestoreInProgress.value = true
        try {
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
        } finally {
            sessionRestoreInProgress.value = false
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

    fun toggleRoutingApp(packageName: String) {
        viewModelScope.launch {
            val prefs = preferencesRepository.preferences.first()
            val updated = prefs.routingApps.toMutableSet().apply {
                if (!add(packageName)) remove(packageName)
            }
            preferencesRepository.setRoutingApps(updated)
        }
    }

    fun toggleAutoConnect(packageName: String) {
        viewModelScope.launch {
            val prefs = preferencesRepository.preferences.first()
            val updated = prefs.autoConnectApps.toMutableSet().apply {
                if (!add(packageName)) remove(packageName)
            }
            preferencesRepository.setAutoConnectApps(updated)
            syncAutomationMonitor()
        }
    }

    fun toggleAutoDisconnect(packageName: String) {
        viewModelScope.launch {
            val prefs = preferencesRepository.preferences.first()
            val updated = prefs.autoDisconnectApps.toMutableSet().apply {
                if (!add(packageName)) remove(packageName)
            }
            preferencesRepository.setAutoDisconnectApps(updated)
            syncAutomationMonitor()
        }
    }

    fun setRoutingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setRoutingEnabled(enabled)
        }
    }

    fun setAutoConnectEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setAutoConnectEnabled(enabled)
            syncAutomationMonitor()
        }
    }

    fun setAutoDisconnectEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setAutoDisconnectEnabled(enabled)
            syncAutomationMonitor()
        }
    }

    fun setAwayModeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setAwayModeEnabled(enabled)
            syncAutomationMonitor()
            val isRunning =
                VpnStatusBus.status.value.state == ConnectionState.CONNECTED ||
                    VpnStatusBus.status.value.state == ConnectionState.CONNECTING
            if (isRunning) {
                vpnRepository.stopVpn()
                delay(700)
                connect()
            }
        }
    }

    fun resetAppPoliciesToRecommended() {
        viewModelScope.launch {
            val access = resolvedAccess.value
            if (access == null) {
                message.value = "Профиль доступа еще не готов"
                return@launch
            }
            preferencesRepository.resetPolicyDefaults(
                routingPolicy = access.routingPolicy,
                automationPolicy = access.automationPolicy
            )
            syncAutomationMonitor()
            message.value = "Рекомендованные списки восстановлены"
        }
    }

    fun connect() {
        viewModelScope.launch {
            val prefs: UserPreferences = preferencesRepository.preferences.first()
            if (!isAuthenticated.value && prefs.deviceToken.length != REQUIRED_TOKEN_LENGTH) {
                message.value = "Сначала войдите по токену"
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
                    val profile = activeProfile(access, prefs)
                    val effectiveRoutingPolicy = effectiveRoutingPolicy(
                        profile = profile,
                        prefs = prefs
                    )
                    val effectiveAutomationPolicy = effectiveAutomationPolicy(
                        profile = profile,
                        prefs = prefs
                    )
                    vpnRepository.startVpn(
                        node = profile.node,
                        xrayConfig = profile.xrayConfig,
                        routingPolicy = effectiveRoutingPolicy,
                        automationPolicy = effectiveAutomationPolicy,
                        autoDisconnectApps = emptySet(),
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

    fun refreshVpnEnvironment() {
        vpnTransportActive.value = detectVpnTransport()
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

        enterUnauthenticatedRestoreFailure(error)
        if (error is AccessNotReadyException) {
            retryPendingAccessUntilReady(autoConnect = false)
        }
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
            retryPendingAccessUntilReady(autoConnect = true)
            return
        }

        handleUnauthenticatedFailure(error)
    }

    private suspend fun applyAuthenticatedAccess(
        access: ResolvedNodeAccess,
        autoConnect: Boolean
    ) {
        preferencesRepository.applyPolicyDefaultsIfNeeded(
            routingPolicy = access.routingPolicy,
            automationPolicy = access.automationPolicy
        )
        resolvedAccess.value = access
        loadedNodes.value = access.profiles.values
            .map { profile -> profile.node }
            .distinctBy { node -> node.id }
            .ifEmpty { listOf(access.node) }
        preferencesRepository.setSelectedNode(activeProfile(access, preferencesRepository.preferences.first()).node.id)
        isAuthenticated.value = true
        pendingAutoConnect.value = autoConnect
        syncAutomationMonitor()
        authError.value = null
        message.value = null
    }

    private fun effectiveRoutingPolicy(
        profile: ResolvedAccessProfile,
        prefs: UserPreferences
    ): AppRoutingPolicy {
        if (prefs.awayModeEnabled) {
            val awayApps = prefs.autoDisconnectApps.normalizedPackages()
            return if (awayApps.isEmpty()) {
                AppRoutingPolicy(mode = AppRoutingPolicy.MODE_ALL_APPS)
            } else {
                profile.routingPolicy.copy(
                    mode = AppRoutingPolicy.MODE_SELECTED_APPS,
                    includedApps = awayApps,
                    excludedApps = emptySet()
                )
            }
        }

        val routingApps = prefs.routingApps.normalizedPackages()
        if (!prefs.routingEnabled || routingApps.isEmpty()) {
            return AppRoutingPolicy(mode = AppRoutingPolicy.MODE_ALL_APPS)
        }

        return profile.routingPolicy.copy(
            mode = AppRoutingPolicy.MODE_SELECTED_APPS,
            includedApps = routingApps,
            excludedApps = emptySet()
        )
    }

    private fun effectiveAutomationPolicy(
        profile: ResolvedAccessProfile,
        prefs: UserPreferences
    ): AppAutomationPolicy {
        if (prefs.awayModeEnabled) {
            val awayApps = prefs.autoDisconnectApps.normalizedPackages()
            return profile.automationPolicy.copy(
                autoConnectApps = awayApps,
                autoDisconnectApps = emptySet(),
                requiresUsageAccess = awayApps.isNotEmpty()
            )
        }

        return AppAutomationPolicy(
            autoConnectApps = if (prefs.autoConnectEnabled) {
                prefs.autoConnectApps.normalizedPackages()
            } else {
                emptySet()
            },
            autoDisconnectApps = if (prefs.autoDisconnectEnabled) {
                prefs.autoDisconnectApps.normalizedPackages()
            } else {
                emptySet()
            },
            requiresUsageAccess = prefs.autoConnectEnabled || prefs.autoDisconnectEnabled
        )
    }

    private fun Iterable<String>.normalizedPackages(): Set<String> {
        return map { item -> item.trim() }
            .filter { item -> item.isNotBlank() }
            .toSet()
    }

    private fun activeProfile(
        access: ResolvedNodeAccess,
        prefs: UserPreferences
    ): ResolvedAccessProfile {
        if (prefs.awayModeEnabled) {
            access.profiles[AWAY_PROFILE_ID]?.let { return it }
        }

        return access.profiles[NORMAL_PROFILE_ID] ?: ResolvedAccessProfile(
            id = NORMAL_PROFILE_ID,
            label = "Обычный режим",
            node = access.node,
            xrayConfig = access.xrayConfig,
            protocol = access.protocol,
            routingPolicy = access.routingPolicy,
            automationPolicy = access.automationPolicy
        )
    }

    private fun enterAuthenticatedPendingState(error: Throwable) {
        resolvedAccess.value = null
        loadedNodes.value = emptyList()
        isAuthenticated.value = true
        pendingAutoConnect.value = false
        authError.value = null
        message.value = error.message ?: ACCESS_NOT_READY_MESSAGE
    }

    private suspend fun retryPendingAccessUntilReady(autoConnect: Boolean) {
        repeat(PENDING_ACCESS_RETRY_COUNT) { attempt ->
            delay(PENDING_ACCESS_RETRY_DELAY_MS)
            val retry = vpnRepository.refreshAccessBundle()
            val shouldStop = retry.fold(
                onSuccess = { access ->
                    applyAuthenticatedAccess(
                        access = access,
                        autoConnect = autoConnect
                    )
                    true
                },
                onFailure = { error ->
                    when {
                        isInvalidSessionFailure(error) -> {
                            invalidateSession(error)
                            true
                        }
                        error is AccessNotReadyException -> {
                            message.value = if (attempt + 1 >= PENDING_ACCESS_RETRY_COUNT) {
                                ACCESS_NOT_READY_MESSAGE
                            } else {
                                "Готовим доступ устройства..."
                            }
                            false
                        }
                        else -> {
                            message.value = error.message ?: ACCESS_NOT_READY_MESSAGE
                            true
                        }
                    }
                }
            )
            if (shouldStop) {
                return
            }
        }
    }

    private fun handleUnauthenticatedFailure(error: Throwable) {
        resolvedAccess.value = null
        loadedNodes.value = emptyList()
        isAuthenticated.value = false
        pendingAutoConnect.value = false
        authError.value = error.message ?: INVALID_TOKEN_MESSAGE
        message.value = null
    }

    private fun enterUnauthenticatedRestoreFailure(error: Throwable) {
        resolvedAccess.value = null
        loadedNodes.value = emptyList()
        isAuthenticated.value = false
        pendingAutoConnect.value = false
        authError.value = null
        message.value = when (error) {
            is AccessNotReadyException -> ACCESS_NOT_READY_MESSAGE
            else -> error.message ?: "Could not verify device access"
        }
    }

    private suspend fun invalidateSession(
        error: Throwable,
        clearMessage: Boolean = false
    ) {
        vpnRepository.stopVpn()
        vpnRepository.stopAutomationMonitor()
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

    private suspend fun syncAutomationMonitor() {
        val prefs = preferencesRepository.preferences.first()
        val autoConnectActive =
            if (prefs.awayModeEnabled) {
                prefs.autoDisconnectApps.isNotEmpty()
            } else {
                prefs.autoConnectEnabled && prefs.autoConnectApps.isNotEmpty()
            }
        val autoDisconnectActive =
            !prefs.awayModeEnabled &&
                prefs.autoDisconnectEnabled &&
                prefs.autoDisconnectApps.isNotEmpty()
        val shouldRun =
            prefs.deviceToken.trim().length == REQUIRED_TOKEN_LENGTH &&
                (autoConnectActive || autoDisconnectActive)

        if (shouldRun) {
            if (!vpnRepository.hasUsageAccess()) {
                vpnRepository.stopAutomationMonitor()
                if (!usageAccessPromptShown) {
                    message.value = "Для Auto ON/OFF нужен Usage Access"
                    usageAccessPromptShown = true
                }
                return
            }

            usageAccessPromptShown = false
            Log.i(
                TAG,
                "Starting automation monitor: autoOn=$autoConnectActive autoOff=$autoDisconnectActive"
            )
            vpnRepository.startAutomationMonitor()
        } else {
            usageAccessPromptShown = false
            Log.i(
                TAG,
                "Stopping automation monitor: token=${prefs.deviceToken.trim().length} autoOn=$autoConnectActive autoOff=$autoDisconnectActive"
            )
            vpnRepository.stopAutomationMonitor()
        }
    }

    private fun isInvalidSessionFailure(error: Throwable): Boolean {
        return error is InvalidTokenException || error is NoActiveAccessException
    }

    private fun startVpnEnvironmentMonitor() {
        refreshVpnEnvironment()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                refreshVpnEnvironment()
            }

            override fun onLost(network: Network) {
                refreshVpnEnvironment()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                refreshVpnEnvironment()
            }
        }
        runCatching {
            connectivityManager.registerNetworkCallback(request, callback)
            vpnNetworkCallback = callback
        }.onFailure { error ->
            Log.w(TAG, "VPN environment monitor is unavailable", error)
        }
    }

    private fun detectVpnTransport(): Boolean {
        return runCatching {
            connectivityManager.allNetworks.any { network ->
                connectivityManager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        }.getOrDefault(false)
    }

    private companion object {
        const val TAG = "MainViewModel"
        const val REQUIRED_TOKEN_LENGTH = 32
        const val PENDING_ACCESS_RETRY_COUNT = 10
        const val PENDING_ACCESS_RETRY_DELAY_MS = 1_500L
        const val INVALID_TOKEN_MESSAGE = "Некорректный токен"
        const val ACCESS_NOT_READY_MESSAGE = "Доступ пока не готов"
        const val NORMAL_PROFILE_ID = "normal"
        const val AWAY_PROFILE_ID = "away"
    }
}
