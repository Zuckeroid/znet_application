package com.znet.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.znet.app.MainActivity
import com.znet.app.R
import com.znet.app.data.model.ConnectionState
import com.znet.app.data.model.ServerNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import android.app.usage.UsageStatsManager

class ZnetVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val xrayEngine by lazy { Libv2rayEngine(this) }
    private val json by lazy { Json { ignoreUnknownKeys = true } }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var monitorJobActive = false
    private var allowedApps: Set<String> = emptySet()
    private var disallowedApps: Set<String> = emptySet()
    private var autoDisconnectApps: Set<String> = emptySet()
    private var lastLoggedForegroundPackage: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val nodeJson = intent.getStringExtra(EXTRA_NODE).orEmpty()
                val xrayConfig = intent.getStringExtra(EXTRA_XRAY_CONFIG).orEmpty()
                val allowedApps = intent.getStringArrayExtra(EXTRA_ALLOWED_APPS)?.toSet().orEmpty()
                val disallowedApps = intent.getStringArrayExtra(EXTRA_SPLIT_TUNNEL_APPS)?.toSet().orEmpty()
                val autoApps = intent.getStringArrayExtra(EXTRA_AUTO_DISCONNECT_APPS)?.toSet().orEmpty()
                val latency = intent.getLongExtra(EXTRA_LATENCY_MS, -1L)

                if (nodeJson.isBlank() || xrayConfig.isBlank()) {
                    VpnStatusBus.update {
                        it.copy(
                            state = ConnectionState.ERROR,
                            errorMessage = "Профиль подключения не готов"
                        )
                    }
                    return START_NOT_STICKY
                }

                val node = runCatching { json.decodeFromString<ServerNode>(nodeJson) }
                    .getOrElse {
                        VpnStatusBus.update {
                            it.copy(
                                state = ConnectionState.ERROR,
                                errorMessage = "Не удалось прочитать профиль сервера"
                            )
                        }
                        return START_NOT_STICKY
                    }

                serviceScope.launch {
                    connect(node, xrayConfig, allowedApps, disallowedApps, autoApps, latency)
                }
            }

            ACTION_DISCONNECT -> {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(
                        title = getString(R.string.app_name),
                        content = "Отключаем VPN..."
                    )
                )
                serviceScope.launch {
                    disconnect(ConnectionState.DISCONNECTED, null)
                    stopSelf()
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        runBlocking {
            disconnect(ConnectionState.DISCONNECTED, null)
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun connect(
        node: ServerNode,
        xrayConfig: String,
        allowedApps: Set<String>,
        disallowedApps: Set<String>,
        autoApps: Set<String>,
        latencyMs: Long
    ) {
        runCatching {
            VpnStatusBus.update {
                it.copy(
                    state = ConnectionState.CONNECTING,
                    currentNode = node,
                    latencyMs = latencyMs,
                    errorMessage = null
                )
            }
            startForeground(
                NOTIFICATION_ID,
                buildNotification(
                    title = getString(R.string.app_name),
                    content = "Подключаем VPN..."
                )
            )

            this.allowedApps = allowedApps
            this.disallowedApps = disallowedApps
            autoDisconnectApps = autoApps
            appendDebug(
                "vpn connect requested allowed=${this.allowedApps.size} disallowed=${this.disallowedApps.size} autoOff=${autoDisconnectApps.size}"
            )
            establishTun(
                allowedApps = this.allowedApps,
                disallowedApps = this.disallowedApps
            )
            xrayEngine.ensureReady()
            xrayEngine.start(xrayConfig, vpnInterface)
            startAppMonitor()

            VpnStatusBus.update {
                it.copy(
                    state = ConnectionState.CONNECTED,
                    currentNode = node,
                    latencyMs = latencyMs,
                    errorMessage = null
                )
            }
            updateNotification("${node.notificationLabel()} ✅")
        }.onFailure { error ->
            Log.e(TAG, "connect failed", error)
            VpnStatusBus.update {
                it.copy(
                    state = ConnectionState.ERROR,
                    currentNode = node,
                    errorMessage = error.message ?: "Не удалось подключиться"
                )
            }
            disconnect(ConnectionState.ERROR, error.message)
            stopSelf()
        }
    }

    private fun establishTun(
        allowedApps: Set<String>,
        disallowedApps: Set<String>
    ) {
        vpnInterface?.close()

        val builder = Builder()
            .setSession("znet")
            .setMtu(1500)
            .addAddress("10.200.0.2", 24)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")

        val needsBrowserSafeTunnel = allowedApps.any { packageName ->
            packageName in CHROMIUM_BROWSER_PACKAGES
        }

        if (allowedApps.isNotEmpty() && !needsBrowserSafeTunnel) {
            var acceptedAllowedApps = 0
            allowedApps
                .filterNot { allowedPackage -> allowedPackage == packageName }
                .forEach { allowedPackage ->
                    runCatching {
                        builder.addAllowedApplication(allowedPackage)
                        acceptedAllowedApps += 1
                    }.onFailure { error ->
                        Log.w(TAG, "Allowed app is unavailable: $allowedPackage", error)
                    }
                }

            if (acceptedAllowedApps == 0) {
                error("В выбранном списке нет установленных приложений")
            }
        } else {
            if (needsBrowserSafeTunnel) {
                appendDebug("vpn browser safe full tunnel enabled")
                Log.i(TAG, "Browser safe full tunnel enabled for selected Chromium app")
            }
            (disallowedApps + packageName).forEach { blockedPackage ->
                runCatching {
                    builder.addDisallowedApplication(blockedPackage)
                }.onFailure { error ->
                    Log.w(TAG, "Disallowed app is unavailable: $blockedPackage", error)
                }
            }
        }

        vpnInterface = builder.establish() ?: error("Не удалось создать VPN-интерфейс")
    }

    private suspend fun disconnect(state: ConnectionState, message: String?) {
        monitorJobActive = false
        appendDebug("vpn disconnect state=$state message=${message.orEmpty()}")

        runCatching {
            xrayEngine.stop()
        }
        runCatching {
            vpnInterface?.close()
            vpnInterface = null
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        VpnStatusBus.update {
            it.copy(
                state = state,
                errorMessage = message,
                currentNode = if (state == ConnectionState.DISCONNECTED) null else it.currentNode
            )
        }
    }

    private fun startAppMonitor() {
        if (monitorJobActive || autoDisconnectApps.isEmpty()) return
        monitorJobActive = true
        appendDebug("vpn monitor started autoOff=${autoDisconnectApps.size}")

        serviceScope.launch {
            while (isActive && monitorJobActive) {
                val foregroundPackage = resolveForegroundPackage()
                if (!foregroundPackage.isNullOrBlank() && foregroundPackage != lastLoggedForegroundPackage) {
                    appendDebug("vpn foreground: $foregroundPackage")
                    lastLoggedForegroundPackage = foregroundPackage
                }
                if (!foregroundPackage.isNullOrBlank() && autoDisconnectApps.contains(foregroundPackage)) {
                    appendDebug("vpn auto off matched: $foregroundPackage")
                    disconnect(
                        state = ConnectionState.PAUSED_BY_RULE,
                        message = "Отключено правилом Auto OFF"
                    )
                    stopSelf()
                    return@launch
                }
                delay(1_500)
            }
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

    private fun appendDebug(message: String) {
        runCatching {
            filesDir.resolve("automation_debug.log")
                .appendText("${System.currentTimeMillis()} $message\n")
        }
    }

    private fun buildNotification(
        title: String,
        content: String? = null
    ): Notification {
        createNotificationChannel()

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(R.drawable.ic_stat_znet)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)

        if (!content.isNullOrBlank()) {
            builder.setContentText(content)
        }

        return builder.build()
    }

    private fun updateNotification(title: String) {
        val notification = buildNotification(title)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.vpn_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun ServerNode.notificationLabel(): String {
        val safeName = name.trim()
            .takeIf { it.isNotBlank() && !it.isIpAddressLike() }
            ?: id.trim().takeIf { it.isNotBlank() && !it.isIpAddressLike() }
            ?: "Znet node"
        val flag = flagEmoji.trim().takeIf { it.isNotBlank() }
        return listOfNotNull(flag, safeName).joinToString(" ")
    }

    private fun String.isIpAddressLike(): Boolean {
        val value = trim()
        return value.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) || value.contains(":")
    }

    companion object {
        private const val TAG = "ZnetVpnService"
        private const val CHANNEL_ID = "znet_vpn_channel"
        private const val NOTIFICATION_ID = 7_001
        private const val FOREGROUND_LOOKBACK_MS = 10_000L
        private val CHROMIUM_BROWSER_PACKAGES = setOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary",
            "com.microsoft.emmx",
            "com.brave.browser",
            "com.yandex.browser",
            "com.yandex.browser.beta",
            "com.opera.browser",
            "com.opera.browser.beta",
            "com.vivaldi.browser",
            "com.kiwibrowser.browser"
        )

        const val ACTION_CONNECT = "com.znet.app.action.CONNECT"
        const val ACTION_DISCONNECT = "com.znet.app.action.DISCONNECT"

        const val EXTRA_NODE = "extra_node"
        const val EXTRA_XRAY_CONFIG = "extra_xray_config"
        const val EXTRA_ALLOWED_APPS = "extra_allowed_apps"
        const val EXTRA_SPLIT_TUNNEL_APPS = "extra_split_tunnel_apps"
        const val EXTRA_AUTO_DISCONNECT_APPS = "extra_auto_disconnect_apps"
        const val EXTRA_LATENCY_MS = "extra_latency_ms"
    }
}
