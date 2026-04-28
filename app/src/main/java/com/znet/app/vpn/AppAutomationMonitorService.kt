package com.znet.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.znet.app.MainActivity
import com.znet.app.R
import com.znet.app.ZnetApp
import com.znet.app.data.UserPreferences
import com.znet.app.data.model.ConnectionState
import com.znet.app.data.remote.AppAutomationPolicy
import com.znet.app.data.remote.AppRoutingPolicy
import com.znet.app.data.repo.ResolvedAccessProfile
import com.znet.app.data.repo.ResolvedNodeAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AppAutomationMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val container by lazy { (application as ZnetApp).container }
    private var monitorStarted = false
    private var lastAutoConnectAttemptMs = 0L
    private var lastLoggedForegroundPackage: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startMonitor()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        monitorStarted = false
        super.onDestroy()
    }

    private fun startMonitor() {
        if (monitorStarted) return
        monitorStarted = true
        startForeground(NOTIFICATION_ID, buildNotification())
        Log.i(TAG, "Automation monitor started")
        appendDebug("monitor started")

        serviceScope.launch {
            while (isActive) {
                runCatching {
                    tick()
                }.onFailure { error ->
                    Log.w(TAG, "Automation monitor tick failed", error)
                    appendDebug("tick failed: ${error.message}")
                }
                delay(MONITOR_INTERVAL_MS)
            }
        }
    }

    private suspend fun tick() {
        val prefs = container.preferencesRepository.preferences.first()
        val hasSession = prefs.deviceToken.trim().length == REQUIRED_TOKEN_LENGTH
        val autoConnectApps = effectiveAutoConnectApps(prefs)
        val autoDisconnectApps = effectiveAutoDisconnectApps(prefs)
        val autoConnectActive = autoConnectApps.isNotEmpty()
        val autoDisconnectActive =
            autoDisconnectApps.isNotEmpty()
        val hasAutomation = autoConnectActive || autoDisconnectActive

        if (!hasSession || !hasAutomation) {
            Log.i(
                TAG,
                "Automation monitor stopping: hasSession=$hasSession autoOn=$autoConnectActive autoOff=$autoDisconnectActive"
            )
            appendDebug(
                "monitor stopping: hasSession=$hasSession autoOn=$autoConnectActive autoOff=$autoDisconnectActive"
            )
            stopSelf()
            return
        }

        val foregroundPackage = resolveForegroundPackage() ?: run {
            return
        }
        if (foregroundPackage != lastLoggedForegroundPackage) {
            Log.i(TAG, "Foreground app: $foregroundPackage")
            appendDebug("foreground: $foregroundPackage")
            lastLoggedForegroundPackage = foregroundPackage
        }
        if (foregroundPackage == packageName) return

        if (autoDisconnectActive && autoDisconnectApps.contains(foregroundPackage)
        ) {
            val currentState = VpnStatusBus.status.value.state
            Log.i(TAG, "Auto OFF matched $foregroundPackage, stopping VPN; currentState=$currentState")
            appendDebug("auto off matched: $foregroundPackage state=$currentState")
            container.vpnRepository.stopVpn()
            appendDebug("auto off stop requested: $foregroundPackage")
            return
        }

        if (!autoConnectActive || !autoConnectApps.contains(foregroundPackage)) {
            return
        }

        val currentState = VpnStatusBus.status.value.state
        if (currentState != ConnectionState.DISCONNECTED &&
            currentState != ConnectionState.ERROR &&
            currentState != ConnectionState.PAUSED_BY_RULE
        ) {
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastAutoConnectAttemptMs < AUTO_CONNECT_COOLDOWN_MS) {
            return
        }
        lastAutoConnectAttemptMs = now

        if (VpnService.prepare(this) != null) {
            Log.i(TAG, "VPN permission is required before automation can connect")
            appendDebug("vpn permission required")
            return
        }

        Log.i(TAG, "Auto ON matched $foregroundPackage, starting VPN")
        appendDebug("auto on matched: $foregroundPackage")
        container.vpnRepository.refreshAccessBundle()
            .onSuccess { access -> startVpnFromAutomation(access, prefs) }
            .onFailure { error ->
                Log.w(TAG, "Auto-connect access refresh failed", error)
                appendDebug("auto on refresh failed: ${error.message}")
            }
    }

    private fun startVpnFromAutomation(access: ResolvedNodeAccess, prefs: UserPreferences) {
        val profile = activeProfile(access, prefs)
        container.vpnRepository.startVpn(
            node = profile.node,
            xrayConfig = profile.xrayConfig,
            routingPolicy = effectiveRoutingPolicy(profile, prefs),
            automationPolicy = effectiveAutomationPolicy(profile, prefs),
            autoDisconnectApps = emptySet(),
            latencyMs = -1L
        )
    }

    private fun effectiveRoutingPolicy(
        profile: ResolvedAccessProfile,
        prefs: UserPreferences
    ): AppRoutingPolicy {
        if (prefs.awayModeEnabled) {
            val awayApps = effectiveAutoConnectApps(prefs)
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
            val awayApps = effectiveAutoConnectApps(prefs)
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

    private fun effectiveAutoConnectApps(prefs: UserPreferences): Set<String> {
        return if (prefs.awayModeEnabled) {
            prefs.autoDisconnectApps.normalizedPackages()
        } else if (prefs.autoConnectEnabled) {
            prefs.autoConnectApps.normalizedPackages()
        } else {
            emptySet()
        }
    }

    private fun effectiveAutoDisconnectApps(prefs: UserPreferences): Set<String> {
        return if (prefs.awayModeEnabled || !prefs.autoDisconnectEnabled) {
            emptySet()
        } else {
            prefs.autoDisconnectApps.normalizedPackages()
        }
    }

    private fun resolveForegroundPackage(): String? {
        val manager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val start = end - FOREGROUND_LOOKBACK_MS
        val events = manager.queryEvents(start, end)
        val event = UsageEvents.Event()
        var foregroundPackage: String? = null
        var foregroundAt = 0L

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val isForegroundEvent =
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        event.eventType == UsageEvents.Event.ACTIVITY_RESUMED)
            if (isForegroundEvent && event.timeStamp >= foregroundAt) {
                foregroundPackage = event.packageName
                foregroundAt = event.timeStamp
            }
        }

        if (!foregroundPackage.isNullOrBlank()) {
            return foregroundPackage
        }

        val usage = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
        return usage.maxByOrNull { it.lastTimeUsed }?.packageName
    }

    private fun buildNotification(): Notification {
        createNotificationChannel()

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Автоматическое подключение активно")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Znet automation",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun Iterable<String>.normalizedPackages(): Set<String> {
        return map { item -> item.trim() }
            .filter { item -> item.isNotBlank() }
            .toSet()
    }

    private fun appendDebug(message: String) {
        runCatching {
            filesDir.resolve("automation_debug.log")
                .appendText("${System.currentTimeMillis()} $message\n")
        }
    }

    companion object {
        private const val TAG = "ZnetAutomation"
        private const val CHANNEL_ID = "znet_automation_channel"
        private const val NOTIFICATION_ID = 7_002
        private const val REQUIRED_TOKEN_LENGTH = 32
        private const val MONITOR_INTERVAL_MS = 1_500L
        private const val FOREGROUND_LOOKBACK_MS = 10_000L
        private const val AUTO_CONNECT_COOLDOWN_MS = 15_000L
        private const val NORMAL_PROFILE_ID = "normal"
        private const val AWAY_PROFILE_ID = "away"

        const val ACTION_START = "com.znet.app.action.START_AUTOMATION"
        const val ACTION_STOP = "com.znet.app.action.STOP_AUTOMATION"
    }
}
