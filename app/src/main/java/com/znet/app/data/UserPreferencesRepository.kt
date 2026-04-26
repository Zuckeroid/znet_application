package com.znet.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.znet.app.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.userPrefsStore: DataStore<Preferences> by preferencesDataStore(name = "user_prefs")

data class UserPreferences(
    val orchestratorBaseUrl: String,
    val authToken: String,
    val manualVlessLink: String,
    val deviceToken: String,
    val hasActiveAccess: String,
    val activeSubscriptionLinks: String,
    val filteredSubscriptionLinks: String,
    val selectedSubscriptionLink: String,
    val deviceId: String,
    val selectedNodeId: String?,
    val splitTunnelApps: Set<String>,
    val autoDisconnectApps: Set<String>,
    val adaptiveEnabled: Boolean
)

class UserPreferencesRepository(
    private val context: Context
) {
    private object Keys {
        val orchestratorBaseUrl = stringPreferencesKey("orchestrator_base_url")
        val authToken = stringPreferencesKey("auth_token")
        val manualVlessLink = stringPreferencesKey("manual_vless_link")
        val deviceToken = stringPreferencesKey("device_token")
        val hasActiveAccess = stringPreferencesKey("has_active_access")
        val activeSubscriptionLinks = stringPreferencesKey("active_subscription_links")
        val filteredSubscriptionLinks = stringPreferencesKey("filtered_subscription_links")
        val selectedSubscriptionLink = stringPreferencesKey("selected_subscription_link")
        val deviceId = stringPreferencesKey("device_id")
        val selectedNodeId = stringPreferencesKey("selected_node_id")
        val splitTunnelApps = stringSetPreferencesKey("split_tunnel_apps")
        val autoDisconnectApps = stringSetPreferencesKey("auto_disconnect_apps")
        val adaptiveEnabled = booleanPreferencesKey("adaptive_enabled")
    }

    val preferences: Flow<UserPreferences> = context.userPrefsStore.data
        .catch { e ->
            if (e is IOException) {
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { prefs ->
            UserPreferences(
                orchestratorBaseUrl = prefs[Keys.orchestratorBaseUrl] ?: BuildConfig.AUTH_API_URL,
                authToken = prefs[Keys.authToken] ?: "",
                manualVlessLink = prefs[Keys.manualVlessLink] ?: "",
                deviceToken = prefs[Keys.deviceToken] ?: "",
                hasActiveAccess = prefs[Keys.hasActiveAccess] ?: "",
                activeSubscriptionLinks = prefs[Keys.activeSubscriptionLinks] ?: "",
                filteredSubscriptionLinks = prefs[Keys.filteredSubscriptionLinks] ?: "",
                selectedSubscriptionLink = prefs[Keys.selectedSubscriptionLink] ?: "",
                deviceId = prefs[Keys.deviceId] ?: "",
                selectedNodeId = prefs[Keys.selectedNodeId],
                splitTunnelApps = prefs[Keys.splitTunnelApps] ?: emptySet(),
                autoDisconnectApps = prefs[Keys.autoDisconnectApps] ?: emptySet(),
                adaptiveEnabled = prefs[Keys.adaptiveEnabled] ?: true
            )
        }

    suspend fun setOrchestrator(baseUrl: String, token: String) {
        context.userPrefsStore.edit { prefs ->
            prefs[Keys.orchestratorBaseUrl] = baseUrl.trimEnd('/')
            prefs[Keys.authToken] = token
        }
    }

    suspend fun setAuthToken(token: String) {
        context.userPrefsStore.edit { prefs ->
            prefs[Keys.authToken] = token
        }
    }

    suspend fun setManualVlessLink(link: String) {
        context.userPrefsStore.edit { prefs ->
            prefs[Keys.manualVlessLink] = link.trim()
        }
    }

    suspend fun setTokenAuthMetadata(
        deviceToken: String?,
        hasActiveAccess: Boolean?,
        activeSubscriptionLinks: List<String>,
        filteredSubscriptionLinks: List<String>,
        selectedSubscriptionLink: String?
    ) {
        context.userPrefsStore.edit { prefs ->
            prefs[Keys.deviceToken] = deviceToken?.trim().orEmpty()
            prefs[Keys.hasActiveAccess] = hasActiveAccess?.toString().orEmpty()
            prefs[Keys.activeSubscriptionLinks] = normalizeLinkList(activeSubscriptionLinks)
            prefs[Keys.filteredSubscriptionLinks] = normalizeLinkList(filteredSubscriptionLinks)
            prefs[Keys.selectedSubscriptionLink] = selectedSubscriptionLink?.trim().orEmpty()
        }
    }

    suspend fun setSelectedNode(nodeId: String) {
        context.userPrefsStore.edit { prefs ->
            prefs[Keys.selectedNodeId] = nodeId
        }
    }

    suspend fun setSplitTunnelApps(packages: Set<String>) {
        context.userPrefsStore.edit { prefs ->
            prefs[Keys.splitTunnelApps] = packages
        }
    }

    suspend fun setAutoDisconnectApps(packages: Set<String>) {
        context.userPrefsStore.edit { prefs ->
            prefs[Keys.autoDisconnectApps] = packages
        }
    }

    suspend fun setAdaptiveEnabled(enabled: Boolean) {
        context.userPrefsStore.edit { prefs ->
            prefs[Keys.adaptiveEnabled] = enabled
        }
    }

    suspend fun getOrCreateDeviceId(): String {
        val current = context.userPrefsStore.data.map { it[Keys.deviceId] ?: "" }.first()
        if (current.isNotBlank()) {
            return current
        }
        val generated = java.util.UUID.randomUUID().toString()
        context.userPrefsStore.edit { prefs ->
            prefs[Keys.deviceId] = generated
        }
        return generated
    }

    private fun normalizeLinkList(links: List<String>): String {
        return links
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(separator = "\n")
    }
}
