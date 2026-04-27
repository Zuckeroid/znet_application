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

        serviceScope.launch {
            while (isActive) {
                runCatching {
                    tick()
                }.onFailure { error ->
                    Log.w(TAG, "Automation monitor tick failed", error)
                }
                delay(MONITOR_INTERVAL_MS)
            }
        }
    }

    private suspend fun tick() {
        val prefs = container.preferencesRepository.preferences.first()
        val hasSession = prefs.deviceToken.trim().length == REQUIRED_TOKEN_LENGTH
        val autoConnectActive = prefs.autoConnectEnabled && prefs.autoConnectApps.isNotEmpty()
        val autoDisconnectActive =
            prefs.autoDisconnectEnabled && prefs.autoDisconnectApps.isNotEmpty()
        val hasAutomation = autoConnectActive || autoDisconnectActive

        if (!hasSession || !hasAutomation) {
            stopSelf()
            return
        }

        val foregroundPackage = resolveForegroundPackage() ?: return
        if (foregroundPackage == packageName) return

        if (autoDisconnectActive && prefs.autoDisconnectApps.contains(foregroundPackage)
        ) {
            val currentState = VpnStatusBus.status.value.state
            if (currentState == ConnectionState.CONNECTED || currentState == ConnectionState.CONNECTING) {
                container.vpnRepository.stopVpn()
            }
            return
        }

        if (!autoConnectActive || !prefs.autoConnectApps.contains(foregroundPackage)) {
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
            return
        }

        container.vpnRepository.refreshAccessBundle()
            .onSuccess { access -> startVpnFromAutomation(access, prefs) }
            .onFailure { error -> Log.w(TAG, "Auto-connect access refresh failed", error) }
    }

    private fun startVpnFromAutomation(access: ResolvedNodeAccess, prefs: UserPreferences) {
        container.vpnRepository.startVpn(
            node = access.node,
            xrayConfig = access.xrayConfig,
            routingPolicy = effectiveRoutingPolicy(access, prefs),
            automationPolicy = effectiveAutomationPolicy(prefs),
            autoDisconnectApps = emptySet(),
            latencyMs = -1L
        )
    }

    private fun effectiveRoutingPolicy(
        access: ResolvedNodeAccess,
        prefs: UserPreferences
    ): AppRoutingPolicy {
        val routingApps = prefs.routingApps.normalizedPackages()
        if (!prefs.routingEnabled || routingApps.isEmpty()) {
            return AppRoutingPolicy(mode = AppRoutingPolicy.MODE_ALL_APPS)
        }

        return access.routingPolicy.copy(
            mode = AppRoutingPolicy.MODE_SELECTED_APPS,
            includedApps = routingApps,
            excludedApps = emptySet()
        )
    }

    private fun effectiveAutomationPolicy(prefs: UserPreferences): AppAutomationPolicy {
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
            .setContentText("App automation is active")
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

    companion object {
        private const val TAG = "ZnetAutomation"
        private const val CHANNEL_ID = "znet_automation_channel"
        private const val NOTIFICATION_ID = 7_002
        private const val REQUIRED_TOKEN_LENGTH = 32
        private const val MONITOR_INTERVAL_MS = 1_500L
        private const val FOREGROUND_LOOKBACK_MS = 10_000L
        private const val AUTO_CONNECT_COOLDOWN_MS = 15_000L

        const val ACTION_START = "com.znet.app.action.START_AUTOMATION"
        const val ACTION_STOP = "com.znet.app.action.STOP_AUTOMATION"
    }
}
