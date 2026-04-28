package com.znet.app.ui

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.znet.app.data.model.ConnectionState
import com.znet.app.data.model.InstalledApp
import com.znet.app.data.model.ServerNode
import com.znet.app.data.remote.AppRoutingPolicy
import com.znet.app.viewmodel.MainUiState
import com.znet.app.viewmodel.MainViewModel
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.launch

private enum class BottomTab {
    Home,
    Servers,
    Settings
}

private val NeonGreen = Color(0xFF5BFF74)
private val NeonGreenSoft = Color(0x665BFF74)
private val NeonMuted = Color(0xFF6D7D8A)
private val NeonButtonOutline = Color(0xFF48FF70)
private val NeonButtonBg = Color(0xFF0A2413)

private data class PolicyAppItem(
    val packageName: String,
    val label: String,
    val installed: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZnetAppScreen(
    state: MainUiState,
    viewModel: MainViewModel
) {
    var selectedTab by rememberSaveable { mutableStateOf(BottomTab.Home) }
    val context = LocalContext.current
    val activity = context as? Activity
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshVpnEnvironment()
                viewModel.validateDeviceSessionOnForeground()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.connect()
        } else {
            scope.launch { snackbarHostState.showSnackbar("VPN permission is required") }
        }
    }

    LaunchedEffect(state.message) {
        val text = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.clearMessage()
    }

    fun connectOrDisconnect() {
        if (state.connectionState == ConnectionState.CONNECTED || state.connectionState == ConnectionState.CONNECTING) {
            viewModel.disconnect()
            return
        }

        val intent = VpnService.prepare(context)
        if (intent == null) {
            viewModel.connect()
        } else if (activity != null) {
            vpnPermissionLauncher.launch(intent)
        }
    }

    LaunchedEffect(state.pendingAutoConnect, state.isAuthenticated, state.connectionState) {
        if (!state.isAuthenticated || !state.pendingAutoConnect) return@LaunchedEffect
        if (state.connectionState != ConnectionState.DISCONNECTED && state.connectionState != ConnectionState.ERROR) {
            viewModel.consumeAutoConnectRequest()
            return@LaunchedEffect
        }
        connectOrDisconnect()
        viewModel.consumeAutoConnectRequest()
    }

    if (state.sessionRestoreInProgress) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color(0xFF02070C),
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { padding ->
            SessionRestoreScreen(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        }
        return
    }

    if (!state.isAuthenticated) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color(0xFF02070C),
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { padding ->
            TokenAuthScreen(
                state = state,
                onTokenChanged = viewModel::updateAuthTokenInput,
                onSubmit = viewModel::submitAuth,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        }
        return
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color(0xFF02070C),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF050C13),
                tonalElevation = 0.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == BottomTab.Home,
                    onClick = { selectedTab = BottomTab.Home },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = NeonGreen,
                        selectedTextColor = NeonGreen,
                        indicatorColor = Color(0x221AFF54),
                        unselectedIconColor = NeonMuted,
                        unselectedTextColor = NeonMuted
                    ),
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("Главная") }
                )
                NavigationBarItem(
                    selected = selectedTab == BottomTab.Servers,
                    onClick = { selectedTab = BottomTab.Servers },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = NeonGreen,
                        selectedTextColor = NeonGreen,
                        indicatorColor = Color(0x221AFF54),
                        unselectedIconColor = NeonMuted,
                        unselectedTextColor = NeonMuted
                    ),
                    icon = { Icon(Icons.Default.Storage, contentDescription = null) },
                    label = { Text("Серверы") }
                )
                NavigationBarItem(
                    selected = selectedTab == BottomTab.Settings,
                    onClick = { selectedTab = BottomTab.Settings },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = NeonGreen,
                        selectedTextColor = NeonGreen,
                        indicatorColor = Color(0x221AFF54),
                        unselectedIconColor = NeonMuted,
                        unselectedTextColor = NeonMuted
                    ),
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Настройки") }
                )
            }
        }
    ) { padding ->
        when (selectedTab) {
            BottomTab.Home -> {
                HomeScreen(
                    state = state,
                    onToggleVpn = { connectOrDisconnect() },
                    onAwayModeChanged = viewModel::setAwayModeEnabled,
                    modifier = Modifier.padding(padding)
                )
            }

            BottomTab.Servers -> {
                ServersScreen(
                    state = state,
                    onRefresh = viewModel::refreshNodes,
                    onSelectNode = viewModel::selectNode,
                    modifier = Modifier.padding(padding)
                )
            }

            BottomTab.Settings -> {
                SettingsScreen(
                    state = state,
                    onAdaptiveChanged = viewModel::toggleAdaptive,
                    onToggleRouting = viewModel::toggleRoutingApp,
                    onToggleAutoConnect = viewModel::toggleAutoConnect,
                    onToggleAutoDisconnect = viewModel::toggleAutoDisconnect,
                    onRoutingEnabledChanged = viewModel::setRoutingEnabled,
                    onAutoConnectEnabledChanged = viewModel::setAutoConnectEnabled,
                    onAutoDisconnectEnabledChanged = viewModel::setAutoDisconnectEnabled,
                    onAwayModeChanged = viewModel::setAwayModeEnabled,
                    onResetPolicies = viewModel::resetAppPoliciesToRecommended,
                    onResetZnetVpn = viewModel::disconnect,
                    onOpenVpnSettings = {
                        context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    },
                    onOpenUsageSettings = {
                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    },
                    onOpenNotificationSettings = {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    },
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }
}

@Composable
private fun SessionRestoreScreen(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF01050A), Color(0xFF052313), Color(0xFF01050A))
                )
            )
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Znet",
            color = Color.White,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(16.dp))
        CircularProgressIndicator(
            color = NeonGreen,
            strokeWidth = 2.5.dp
        )
    }
}

@Composable
private fun TokenAuthScreen(
    state: MainUiState,
    onTokenChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uriHandler = LocalUriHandler.current
    val signUpUrl = "${state.webBaseUrl.trimEnd('/')}/signup"
    val signUpText = buildAnnotatedString {
        append("Нет аккаунта? зарегистрироваться ")
        pushStringAnnotation(tag = "signup", annotation = signUpUrl)
        withStyle(
            SpanStyle(
                color = NeonGreen,
                textDecoration = TextDecoration.Underline
            )
        ) {
            append("здесь")
        }
        pop()
    }

    Column(
        modifier = modifier
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF01050A), Color(0xFF052313), Color(0xFF01050A))
                )
            )
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Znet",
            color = Color.White,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Введите токен из личного кабинета",
            color = Color(0xFF9EB0BE)
        )

        Spacer(modifier = Modifier.height(18.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0x3348FF70), RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = Color(0xCC08131D)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = state.authTokenInput,
                    onValueChange = onTokenChanged,
                    label = { Text("Токен доступа") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                if (!state.authError.isNullOrBlank()) {
                    Text(
                        text = state.authError,
                        color = Color(0xFFFF5A5A),
                        fontSize = 12.sp
                    )
                }
                Button(
                    onClick = onSubmit,
                    enabled = !state.authInProgress,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonButtonBg,
                        contentColor = NeonGreen
                    ),
                    border = BorderStroke(1.2.dp, NeonButtonOutline)
                ) {
                    if (state.authInProgress) {
                        CircularProgressIndicator(
                            color = NeonGreen,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Text("Войти")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        ClickableText(
            text = signUpText,
            style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF9EB0BE)),
            onClick = { offset ->
                signUpText.getStringAnnotations(
                    tag = "signup",
                    start = offset,
                    end = offset
                ).firstOrNull()?.let { annotation ->
                    uriHandler.openUri(annotation.item)
                }
            }
        )
    }
}

@Composable
private fun HomeScreen(
    state: MainUiState,
    onToggleVpn: () -> Unit,
    onAwayModeChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val connected = state.connectionState == ConnectionState.CONNECTED
    val statusTitle = if (connected) "Znet подключен" else "Znet отключен"
    val statusText = if (connected) "Соединение защищено" else "Нажмите для подключения"

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF01050A), Color(0xFF062216), Color(0xFF01050A))
                )
            )
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = statusTitle,
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(28.dp))

        Box(
            modifier = Modifier.size(270.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(
                        brush = Brush.radialGradient(
                            colors = if (connected) {
                                listOf(NeonGreenSoft, Color.Transparent)
                            } else {
                                listOf(Color(0x223E4A55), Color.Transparent)
                            }
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .size(235.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF010407))
                    .border(
                        width = 8.dp,
                        brush = Brush.radialGradient(
                            colors = if (connected) {
                                listOf(Color(0xFF8BFF89), Color(0xFF2BE75D), Color(0xFF128234))
                            } else {
                                listOf(Color(0xFF2D3A44), Color(0xFF1B252D), Color(0xFF0E1318))
                            }
                        ),
                        shape = CircleShape
                    )
                    .clickable(onClick = onToggleVpn),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PowerSettingsNew,
                    contentDescription = null,
                    tint = if (connected) NeonGreen else Color(0xFF91A4B5),
                    modifier = Modifier.size(84.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = statusText,
            color = if (connected) NeonGreen else Color(0xFF92A3AF),
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(16.dp))

        val node = state.selectedNode
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, if (connected) Color(0x4446FF6E) else Color(0x3323333F), RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = Color(0xDD09121A)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = if (node == null) {
                        "Нода не выбрана"
                    } else {
                        "Нода: ${node.flagEmoji} ${node.name}"
                    },
                    color = Color(0xFFB6C4CE)
                )
                if (node != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = node.country,
                        color = Color(0xFF90A4B5)
                    )
                }
                if (state.latencyMs >= 0) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Пинг: ${state.latencyMs} ms",
                        color = Color(0xFF54F569)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, if (connected) Color(0x4446FF6E) else Color(0x3323333F), RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = Color(0xDD09121A)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(text = "Подключение", color = Color(0xFF8EA2B2))
                Spacer(modifier = Modifier.height(6.dp))
                InfoRow(
                    label = "Протокол",
                    value = state.protocol?.uppercase().orEmpty().ifBlank { "не определен" }
                )
                InfoRow(
                    label = "Подписка",
                    value = state.serviceTitle.orEmpty().ifBlank { "не определена" }
                )
                InfoRow(
                    label = "Осталось",
                    value = formatDaysRemaining(state.serviceDaysRemaining, state.serviceExpiresAt)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    1.dp,
                    if (state.awayModeEnabled) Color(0x5546FF6E) else Color(0x3323333F),
                    RoundedCornerShape(16.dp)
                ),
            colors = CardDefaults.cardColors(containerColor = Color(0xDD09121A)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("За границей", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = if (state.awayModeAvailable) {
                                "Банки и сервисы из Auto OFF пойдут через российскую ноду."
                            } else {
                                "Профиль появится после настройки away-ноды в оркестраторе."
                            },
                            color = Color(0xFF90A4B5),
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = state.awayModeEnabled,
                        enabled = state.awayModeAvailable,
                        onCheckedChange = onAwayModeChanged
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Color(0xFF90A4B5)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    Spacer(modifier = Modifier.height(6.dp))
}

@Composable
private fun ServersScreen(
    state: MainUiState,
    onRefresh: () -> Unit,
    onSelectNode: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF030A10))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Доступ", color = Color.White, style = MaterialTheme.typography.titleLarge)
            Button(
                onClick = onRefresh,
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonButtonBg,
                    contentColor = NeonGreen
                ),
                border = BorderStroke(1.2.dp, NeonButtonOutline)
            ) {
                Text("Обновить доступ")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (state.nodes.isEmpty()) {
            Text("Доступ еще не получен", color = Color(0xFF9EB0BE))
            return
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(state.nodes, key = { it.id }) { node ->
                NodeCard(
                    node = node,
                    selected = state.selectedNodeId == node.id,
                    onClick = { onSelectNode(node.id) }
                )
            }
        }
    }
}

@Composable
private fun NodeCard(
    node: ServerNode,
    selected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (selected) Color(0xFF4BFF68) else Color(0xFF182733)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF08131D)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(node.flagEmoji, fontSize = 22.sp)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(node.name, color = Color.White, fontWeight = FontWeight.Medium)
                Text("${node.flagEmoji} ${node.country}", color = Color(0xFF90A4B5))
            }
            if (selected) {
                Text("Выбран", color = Color(0xFF4BFF68), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    state: MainUiState,
    onAdaptiveChanged: (Boolean) -> Unit,
    onToggleRouting: (String) -> Unit,
    onToggleAutoConnect: (String) -> Unit,
    onToggleAutoDisconnect: (String) -> Unit,
    onRoutingEnabledChanged: (Boolean) -> Unit,
    onAutoConnectEnabledChanged: (Boolean) -> Unit,
    onAutoDisconnectEnabledChanged: (Boolean) -> Unit,
    onAwayModeChanged: (Boolean) -> Unit,
    onResetPolicies: () -> Unit,
    onResetZnetVpn: () -> Unit,
    onOpenVpnSettings: () -> Unit,
    onOpenUsageSettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    val recommendedRoutingApps = recommendedRoutingPackages(state)
    val recommendedAutoOnApps = state.automationPolicy.autoConnectApps
    val recommendedAutoOffApps = state.automationPolicy.autoDisconnectApps
    val znetVpnActive = state.connectionState == ConnectionState.CONNECTED ||
        state.connectionState == ConnectionState.CONNECTING
    val vpnDiagnosticTitle = when {
        znetVpnActive -> "Активен Znet VPN"
        state.vpnTransportActive -> "Активен другой VPN"
        else -> "VPN не обнаружен"
    }
    val vpnDiagnosticText = when {
        znetVpnActive -> "Туннелем управляет Znet."
        state.vpnTransportActive -> "Android не раскрывает владельца VPN приложению. Откройте системные настройки, чтобы отключить чужой профиль."
        else -> "Можно подключать Znet. Системный Legacy VPN без реального туннеля здесь не учитывается."
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF030A10))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Настройки", color = Color.White, style = MaterialTheme.typography.titleLarge)

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF08131D)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Адаптивная сеть", color = Color.White)
                    Switch(
                        checked = state.adaptiveEnabled,
                        onCheckedChange = onAdaptiveChanged
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onOpenUsageSettings,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonButtonBg,
                        contentColor = NeonGreen
                    ),
                    border = BorderStroke(1.2.dp, NeonButtonOutline)
                ) {
                    Text("Дать доступ Usage Access")
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF08131D)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Уведомления", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(
                    if (notificationsEnabled) {
                        "Znet может показывать активный VPN и автоматику в шторке."
                    } else {
                        "Уведомления выключены, поэтому сервис работает без карточки в шторке."
                    },
                    color = if (notificationsEnabled) Color(0xFF92A6B6) else Color(0xFFFFC857),
                    fontSize = 12.sp
                )
                Button(
                    onClick = onOpenNotificationSettings,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonButtonBg,
                        contentColor = NeonGreen
                    ),
                    border = BorderStroke(1.2.dp, NeonButtonOutline)
                ) {
                    Text("Настройки уведомлений")
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF08131D)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Подключение", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(
                    "Приложение получает доступ только через токен из личного кабинета. Добавление устройств и управление ими происходит на стороне биллинга.",
                    color = Color(0xFF92A6B6),
                    fontSize = 12.sp
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF08131D)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Политики приложений", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(
                    "Рекомендации пришли из оркестратора. Дальше это локальные настройки этого телефона.",
                    color = Color(0xFF92A6B6),
                    fontSize = 12.sp
                )
                Text(
                    "Routing: ${formatRoutingPolicy(state)}",
                    color = Color(0xFF92A6B6),
                    fontSize = 12.sp
                )
                Text(
                    "Auto ON: ${recommendedAutoOnApps.size} рекомендовано, Auto OFF: ${recommendedAutoOffApps.size} рекомендовано",
                    color = Color(0xFF92A6B6),
                    fontSize = 12.sp
                )
                Button(
                    onClick = onResetPolicies,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonButtonBg,
                        contentColor = NeonGreen
                    ),
                    border = BorderStroke(1.2.dp, NeonButtonOutline)
                ) {
                    Text("Сбросить к рекомендованным")
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF08131D)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("За границей", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Российские приложения из Auto OFF будут идти через московскую ноду.",
                            color = Color(0xFF92A6B6),
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = state.awayModeEnabled,
                        enabled = state.awayModeAvailable,
                        onCheckedChange = onAwayModeChanged
                    )
                }
                if (!state.awayModeAvailable) {
                    Text(
                        "Нужна активная away-нода в оркестраторе.",
                        color = Color(0xFFFFC857),
                        fontSize = 12.sp
                    )
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF08131D)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("VPN диагностика", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(vpnDiagnosticTitle, color = if (state.vpnTransportActive || znetVpnActive) NeonGreen else Color.White)
                Text(vpnDiagnosticText, color = Color(0xFF92A6B6), fontSize = 12.sp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onResetZnetVpn,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonButtonBg,
                            contentColor = NeonGreen
                        ),
                        border = BorderStroke(1.2.dp, NeonButtonOutline)
                    ) {
                        Text("Сбросить Znet", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Button(
                        onClick = onOpenVpnSettings,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF101A24),
                            contentColor = Color.White
                        ),
                        border = BorderStroke(1.dp, Color(0x3348FF70))
                    ) {
                        Text("Настройки VPN", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        AppPolicySection(
            title = "Routing",
            description = "Какие приложения идут через VPN. Если секция выключена, туннель работает для всех приложений.",
            enabled = state.routingEnabled,
            selectedPackages = state.routingApps,
            recommendedPackages = recommendedRoutingApps,
            apps = state.installedApps,
            onEnabledChanged = onRoutingEnabledChanged,
            onTogglePackage = onToggleRouting
        )

        AppPolicySection(
            title = "Auto ON",
            description = "Какие приложения могут автоматически поднимать туннель. Для этого нужен Usage Access.",
            enabled = state.autoConnectEnabled,
            selectedPackages = state.autoConnectApps,
            recommendedPackages = recommendedAutoOnApps,
            apps = state.installedApps,
            onEnabledChanged = onAutoConnectEnabledChanged,
            onTogglePackage = onToggleAutoConnect
        )

        AppPolicySection(
            title = "Auto OFF",
            description = "Какие приложения временно выключают VPN. Этот список имеет приоритет над Auto ON.",
            enabled = state.autoDisconnectEnabled,
            selectedPackages = state.autoDisconnectApps,
            recommendedPackages = recommendedAutoOffApps,
            apps = state.installedApps,
            onEnabledChanged = onAutoDisconnectEnabledChanged,
            onTogglePackage = onToggleAutoDisconnect
        )
    }
}

@Composable
private fun AppPolicySection(
    title: String,
    description: String,
    enabled: Boolean,
    selectedPackages: Set<String>,
    recommendedPackages: Set<String>,
    apps: List<InstalledApp>,
    onEnabledChanged: (Boolean) -> Unit,
    onTogglePackage: (String) -> Unit
) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    var query by rememberSaveable("${title}_query") { mutableStateOf("") }
    val normalizedSelected = remember(selectedPackages) { selectedPackages.normalizedPackageSet() }
    val normalizedRecommended = remember(recommendedPackages) { recommendedPackages.normalizedPackageSet() }
    val installedItems = remember(apps) {
        apps.map { app ->
            PolicyAppItem(
                packageName = app.packageName,
                label = app.label,
                installed = true
            )
        }
    }
    val installedPackages = remember(installedItems) {
        installedItems.map { item -> item.packageName }.toSet()
    }
    val missingItems = remember(normalizedSelected, normalizedRecommended, installedPackages) {
        (normalizedSelected + normalizedRecommended)
            .filterNot { packageName -> installedPackages.contains(packageName) }
            .sorted()
            .map { packageName ->
                PolicyAppItem(
                    packageName = packageName,
                    label = packageName,
                    installed = false
                )
            }
    }
    val filteredMissingItems = remember(missingItems, query) {
        missingItems.filterByPolicyQuery(query)
    }
    val filteredInstalledItems = remember(installedItems, normalizedSelected, normalizedRecommended, query) {
        installedItems
            .filterByPolicyQuery(query)
            .sortedWith(
                compareByDescending<PolicyAppItem> { item -> normalizedSelected.contains(item.packageName) }
                    .thenByDescending { item -> normalizedRecommended.contains(item.packageName) }
                    .thenBy { item -> item.label.lowercase() }
                    .thenBy { item -> item.packageName }
            )
    }
    val showRows = expanded || query.isNotBlank()
    val visibleInstalledItems = remember(filteredInstalledItems, showRows) {
        if (showRows) filteredInstalledItems else emptyList()
    }
    val missingSelectedCount = missingItems.count { item -> normalizedSelected.contains(item.packageName) }
    val installedSelectedCount = installedItems.count { item -> normalizedSelected.contains(item.packageName) }

    fun toggleExpanded() {
        expanded = !expanded
        if (!expanded) {
            query = ""
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF08131D)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${normalizedSelected.size} выбрано / ${normalizedRecommended.size} рекомендовано / ${installedItems.size} установлено",
                        color = Color(0xFF92A6B6),
                        fontSize = 12.sp
                    )
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChanged)
            }
            Text(description, color = Color(0xFF92A6B6), fontSize = 12.sp)

            if (missingItems.isNotEmpty() && !showRows) {
                val missingText = if (missingSelectedCount > 0) {
                    "$missingSelectedCount выбрано, но не установлено"
                } else {
                    "${missingItems.size} рекомендовано, но не установлено"
                }
                Text(missingText, color = Color(0xFFFFD36A), fontSize = 12.sp)
            }

            if (showRows) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Поиск приложения") }
                )
            }

            if (showRows) {
                filteredMissingItems.forEach { app ->
                    PolicyAppRow(
                        app = app,
                        enabled = enabled,
                        selected = normalizedSelected.contains(app.packageName),
                        recommended = normalizedRecommended.contains(app.packageName),
                        onTogglePackage = onTogglePackage
                    )
                }

                visibleInstalledItems.forEach { app ->
                    PolicyAppRow(
                        app = app,
                        enabled = enabled,
                        selected = normalizedSelected.contains(app.packageName),
                        recommended = normalizedRecommended.contains(app.packageName),
                        onTogglePackage = onTogglePackage
                    )
                }
            }

            Button(
                onClick = { toggleExpanded() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonButtonBg,
                    contentColor = NeonGreen
                ),
                border = BorderStroke(1.2.dp, NeonButtonOutline)
            ) {
                Text(
                    if (expanded) {
                        "Свернуть список"
                    } else {
                        "Открыть список (${filteredInstalledItems.size})"
                    }
                )
            }

            if (showRows && installedSelectedCount == 0 && normalizedSelected.isNotEmpty()) {
                Text(
                    "Выбранные пакеты сохранятся, но применятся только после установки этих приложений на телефон.",
                    color = Color(0xFF92A6B6),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun PolicyAppRow(
    app: PolicyAppItem,
    enabled: Boolean,
    selected: Boolean,
    recommended: Boolean,
    onTogglePackage: (String) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when {
                !app.installed -> Color(0xFF15130B)
                recommended -> Color(0xFF071A11)
                else -> Color(0xFF071019)
            }
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = selected,
                enabled = enabled,
                onCheckedChange = { onTogglePackage(app.packageName) }
            )
            Spacer(modifier = Modifier.width(8.dp))
            PolicyAppIcon(app = app)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    color = if (app.installed) Color.White else Color(0xFFFFD36A),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!app.installed) {
                    Text(
                        "Нет на телефоне",
                        color = Color(0xFFFFD36A),
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun PolicyAppIcon(app: PolicyAppItem) {
    val context = LocalContext.current
    val iconBitmap = remember(app.packageName, app.installed) {
        if (!app.installed) {
            null
        } else {
            runCatching {
                context.packageManager
                    .getApplicationIcon(app.packageName)
                    .toBitmap(width = POLICY_APP_ICON_PX, height = POLICY_APP_ICON_PX)
                    .asImageBitmap()
            }.getOrNull()
        }
    }

    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (app.installed) Color(0xFF101B25) else Color(0xFF211D11)),
        contentAlignment = Alignment.Center
    ) {
        if (iconBitmap != null) {
            Image(
                bitmap = iconBitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = app.label.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                color = Color(0xFFFFD36A),
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

private fun Iterable<String>.normalizedPackageSet(): Set<String> {
    return map { item -> item.trim() }
        .filter { item -> item.isNotBlank() }
        .toSet()
}

private fun List<PolicyAppItem>.filterByPolicyQuery(query: String): List<PolicyAppItem> {
    val normalizedQuery = query.trim().lowercase()
    if (normalizedQuery.isBlank()) {
        return this
    }

    return filter { item ->
        item.label.lowercase().contains(normalizedQuery) ||
            item.packageName.lowercase().contains(normalizedQuery)
    }
}

private const val POLICY_APP_ICON_PX = 96

private fun recommendedRoutingPackages(state: MainUiState): Set<String> {
    return when (state.routingPolicy.normalizedMode) {
        AppRoutingPolicy.MODE_SELECTED_APPS -> state.routingPolicy.includedApps
        else -> emptySet()
    }
}

private fun formatRoutingPolicy(state: MainUiState): String {
    return when (state.routingPolicy.normalizedMode) {
        AppRoutingPolicy.MODE_SELECTED_APPS ->
            "${state.routingPolicy.includedApps.size} приложений через VPN"
        AppRoutingPolicy.MODE_ALL_EXCEPT ->
            "все, кроме ${state.routingPolicy.excludedApps.size}"
        else -> "все приложения"
    }
}

private fun formatDaysRemaining(
    daysRemaining: Int?,
    expiresAt: String?
): String {
    val suffix = when (daysRemaining) {
        null -> null
        1 -> "1 день"
        in 2..4 -> "$daysRemaining дня"
        else -> "$daysRemaining дней"
    }
    val datePart = expiresAt?.substringBefore('T')?.takeIf { it.isNotBlank() }
    return when {
        suffix != null && datePart != null -> "$suffix, до $datePart"
        suffix != null -> suffix
        datePart != null -> "до $datePart"
        else -> "неизвестно"
    }
}
