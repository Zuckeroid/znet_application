package com.znet.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.znet.app.BuildConfig
import com.znet.app.data.remote.AppAutomationPolicy
import com.znet.app.data.remote.AppRoutingPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.userPrefsStore: DataStore<Preferences> by preferencesDataStore(name = "user_prefs")

data class UserPreferences(
    val deviceToken: String,
    val deviceId: String,
    val selectedNodeId: String?,
    val routingApps: Set<String>,
    val autoConnectApps: Set<String>,
    val autoDisconnectApps: Set<String>,
    val routingEnabled: Boolean,
    val autoConnectEnabled: Boolean,
    val autoDisconnectEnabled: Boolean,
    val awayModeEnabled: Boolean,
    val policyDefaultsApplied: Boolean,
    val adaptiveEnabled: Boolean
)

class UserPreferencesRepository(
    private val context: Context
) {
    private object Keys {
        // Legacy keys are kept only to migrate or wipe stale auth state from older app builds.
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
        val routingApps = stringSetPreferencesKey("routing_apps")
        val autoConnectApps = stringSetPreferencesKey("auto_connect_apps")
        val autoDisconnectApps = stringSetPreferencesKey("auto_disconnect_apps")
        val routingEnabled = booleanPreferencesKey("routing_enabled")
        val autoConnectEnabled = booleanPreferencesKey("auto_connect_enabled")
        val autoDisconnectEnabled = booleanPreferencesKey("auto_disconnect_enabled")
        val awayModeEnabled = booleanPreferencesKey("away_mode_enabled")
        val policyDefaultsApplied = booleanPreferencesKey("policy_defaults_applied")
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
                deviceToken = prefs[Keys.deviceToken] ?: "",
                deviceId = prefs[Keys.deviceId] ?: "",
                selectedNodeId = prefs[Keys.selectedNodeId]?.takeIf { it.isNotBlank() },
                routingApps = prefs[Keys.routingApps] ?: emptySet(),
                autoConnectApps = prefs[Keys.autoConnectApps] ?: emptySet(),
                autoDisconnectApps = prefs[Keys.autoDisconnectApps] ?: emptySet(),
                routingEnabled = prefs[Keys.routingEnabled] ?: true,
                autoConnectEnabled = prefs[Keys.autoConnectEnabled] ?: true,
                autoDisconnectEnabled = prefs[Keys.autoDisconnectEnabled] ?: true,
                awayModeEnabled = prefs[Keys.awayModeEnabled] ?: false,
                policyDefaultsApplied = prefs[Keys.policyDefaultsApplied] ?: false,
                adaptiveEnabled = prefs[Keys.adaptiveEnabled] ?: true
            )
        }

    suspend fun migrateLegacySessionTokenIfNeeded() {
        context.userPrefsStore.edit { prefs ->
            val deviceToken = prefs[Keys.deviceToken]?.trim().orEmpty()
            val legacyAuthToken = prefs[Keys.authToken]?.trim().orEmpty()
            if (deviceToken.isBlank() && legacyAuthToken.length == REQUIRED_TOKEN_LENGTH) {
                prefs[Keys.deviceToken] = legacyAuthToken
            }

            // Drop old session/link leftovers once the active device token has been restored.
            prefs[Keys.orchestratorBaseUrl] = BuildConfig.AUTH_API_URL.trimEnd('/')
            prefs[Keys.authToken] = ""
            prefs[Keys.manualVlessLink] = ""
            prefs[Keys.hasActiveAccess] = ""
            prefs[Keys.activeSubscriptionLinks] = ""
            prefs[Keys.filteredSubscriptionLinks] = ""
            prefs[Keys.selectedSubscriptionLink] = ""
        }
    }

    suspend fun clearAuthSession() {
        context.userPrefsStore.edit { prefs ->
            prefs[Keys.authToken] = ""
            prefs[Keys.manualVlessLink] = ""
            prefs[Keys.deviceToken] = ""
            prefs[Keys.hasActiveAccess] = ""
            prefs[Keys.activeSubscriptionLinks] = ""
            prefs[Keys.filteredSubscriptionLinks] = ""
            prefs[Keys.selectedSubscriptionLink] = ""
            prefs[Keys.selectedNodeId] = ""
        }
    }

    suspend fun setDeviceSession(deviceToken: String) {
        context.userPrefsStore.edit { prefs ->
            prefs[Keys.deviceToken] = deviceToken.trim()
            prefs[Keys.orchestratorBaseUrl] = BuildConfig.AUTH_API_URL.trimEnd('/')
            prefs[Keys.authToken] = ""
            prefs[Keys.manualVlessLink] = ""
            prefs[Keys.hasActiveAccess] = ""
            prefs[Keys.activeSubscriptionLinks] = ""
            prefs[Keys.filteredSubscriptionLinks] = ""
            prefs[Keys.selectedSubscriptionLink] = ""
        }
    }

    suspend fun setSelectedNode(nodeId: String) {
        context.userPrefsStore.edit { prefs ->
            prefs[Keys.selectedNodeId] = nodeId
        }
    }

    suspend fun setRoutingApps(packages: Set<String>) {
        context.userPrefsStore.edit { prefs ->
            prefs[Keys.routingApps] = packages.normalizedPackages()
        }
    }

    suspend fun setAutoConnectApps(packages: Set<String>) {
        context.userPrefsStore.edit { prefs ->
            prefs[Keys.autoConnectApps] = packages.normalizedPackages()
        }
    }

    suspend fun setAutoDisconnectApps(packages: Set<String>) {
        context.userPrefsStore.edit { prefs ->
            prefs[Keys.autoDisconnectApps] = packages.normalizedPackages()
        }
    }

    suspend fun setRoutingEnabled(enabled: Boolean) {
        context.userPrefsStore.edit { prefs ->
            prefs[Keys.routingEnabled] = enabled
        }
    }

    suspend fun setAutoConnectEnabled(enabled: Boolean) {
        context.userPrefsStore.edit { prefs ->
            prefs[Keys.autoConnectEnabled] = enabled
        }
    }

    suspend fun setAutoDisconnectEnabled(enabled: Boolean) {
        context.userPrefsStore.edit { prefs ->
            prefs[Keys.autoDisconnectEnabled] = enabled
        }
    }

    suspend fun setAwayModeEnabled(enabled: Boolean) {
        context.userPrefsStore.edit { prefs ->
            prefs[Keys.awayModeEnabled] = enabled
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

    suspend fun applyPolicyDefaultsIfNeeded(
        routingPolicy: AppRoutingPolicy,
        automationPolicy: AppAutomationPolicy
    ) {
        context.userPrefsStore.edit { prefs ->
            if (prefs[Keys.policyDefaultsApplied] == true) {
                return@edit
            }

            applyPolicyDefaults(
                prefs = prefs,
                routingPolicy = routingPolicy,
                automationPolicy = automationPolicy
            )
        }
    }

    suspend fun resetPolicyDefaults(
        routingPolicy: AppRoutingPolicy,
        automationPolicy: AppAutomationPolicy
    ) {
        context.userPrefsStore.edit { prefs ->
            applyPolicyDefaults(
                prefs = prefs,
                routingPolicy = routingPolicy,
                automationPolicy = automationPolicy
            )
        }
    }

    private fun applyPolicyDefaults(
        prefs: MutablePreferences,
        routingPolicy: AppRoutingPolicy,
        automationPolicy: AppAutomationPolicy
    ) {
        val routingApps = when (routingPolicy.normalizedMode) {
            AppRoutingPolicy.MODE_SELECTED_APPS -> routingPolicy.includedApps
            else -> emptySet()
        }.normalizedPackages()
        val autoConnectApps = automationPolicy.autoConnectApps.normalizedPackages()
        val autoDisconnectApps = automationPolicy.autoDisconnectApps.normalizedPackages()

        prefs[Keys.routingApps] = routingApps
        prefs[Keys.autoConnectApps] = autoConnectApps
        prefs[Keys.autoDisconnectApps] = autoDisconnectApps
        prefs[Keys.routingEnabled] = routingApps.isNotEmpty()
        prefs[Keys.autoConnectEnabled] = autoConnectApps.isNotEmpty()
        prefs[Keys.autoDisconnectEnabled] = autoDisconnectApps.isNotEmpty()
        prefs[Keys.policyDefaultsApplied] = true
    }

    private fun Iterable<String>.normalizedPackages(): Set<String> {
        return map { item -> item.trim() }
            .filter { item -> item.isNotBlank() }
            .toSet()
    }

    private companion object {
        const val REQUIRED_TOKEN_LENGTH = 32
    }
}
