package com.znet.app.ui

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.znet.app.data.model.ConnectionState
import com.znet.app.data.model.ServerNode
import com.znet.app.viewmodel.MainUiState
import com.znet.app.viewmodel.MainViewModel
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
                    onSaveVless = viewModel::saveManualVlessLink,
                    onAdaptiveChanged = viewModel::toggleAdaptive,
                    onToggleSplit = viewModel::toggleSplitTunnel,
                    onToggleAuto = viewModel::toggleAutoDisconnect,
                    onOpenUsageSettings = {
                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
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
private fun TokenAuthScreen(
    state: MainUiState,
    onTokenChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val normalizedToken = state.authTokenInput.trim()
    var lastAutoSubmittedToken by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(normalizedToken, state.authInProgress, state.isAuthenticated) {
        if (
            normalizedToken.length == AUTH_TOKEN_LENGTH &&
            !state.authInProgress &&
            !state.isAuthenticated &&
            normalizedToken != lastAutoSubmittedToken
        ) {
            lastAutoSubmittedToken = normalizedToken
            onSubmit()
        } else if (normalizedToken.length != AUTH_TOKEN_LENGTH) {
            lastAutoSubmittedToken = null
        }
    }

    val uriHandler = LocalUriHandler.current
    val signUpText = buildAnnotatedString {
        append("Нет аккаунта? зарегистрироваться ")
        pushStringAnnotation(tag = "signup", annotation = SIGN_UP_URL)
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
            text = "Enter token to activate VPN access",
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
                    label = { Text("Access token") },
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
                        Text("Sign in")
                    }
                }
                if (state.isDebugMode && !state.debugApiResponse.isNullOrBlank()) {
                    Text(
                        text = "DEBUG API RESPONSE:",
                        color = Color(0xFF7FE890),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = state.debugApiResponse,
                        color = Color(0xFFCFE7D4),
                        fontSize = 12.sp
                    )
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
    modifier: Modifier = Modifier
) {
    val connected = state.connectionState == ConnectionState.CONNECTED
    val statusTitle = if (connected) "Znet подключен" else "Znet отключен"
    val statusText = if (connected) "Соединение защищено" else "Нажмите для подключения"

    Column(
        modifier = modifier
            .fillMaxSize()
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
                    text = if (node == null) "Сервер не выбран" else "Сервер: ${node.flagEmoji} ${node.country}, ${node.city}",
                    color = Color(0xFFB6C4CE)
                )
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
                Text(text = "Трафик", color = Color(0xFF8EA2B2))
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = "RX: ${formatBytes(state.rxBytes)}", color = Color.White)
                Text(text = "TX: ${formatBytes(state.txBytes)}", color = Color.White)
            }
        }
    }
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
            Text("Серверы", color = Color.White, style = MaterialTheme.typography.titleLarge)
            Button(
                onClick = onRefresh,
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonButtonBg,
                    contentColor = NeonGreen
                ),
                border = BorderStroke(1.2.dp, NeonButtonOutline)
            ) {
                Text("Обновить")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (state.nodes.isEmpty()) {
            Text("Ноды не загружены", color = Color(0xFF9EB0BE))
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
                Text("${node.country}, ${node.city}", color = Color(0xFF90A4B5))
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
    onSaveVless: (String) -> Unit,
    onAdaptiveChanged: (Boolean) -> Unit,
    onToggleSplit: (String) -> Unit,
    onToggleAuto: (String) -> Unit,
    onOpenUsageSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var vlessLink by remember(state.manualVlessLink) { mutableStateOf(state.manualVlessLink) }

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
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = vlessLink,
                    onValueChange = { vlessLink = it },
                    label = { Text("VLESS link") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Button(
                    onClick = { onSaveVless(vlessLink) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonButtonBg,
                        contentColor = NeonGreen
                    ),
                    border = BorderStroke(1.2.dp, NeonButtonOutline)
                ) {
                    Text("Сохранить VLESS")
                }
            }
        }

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
                    Text("Adaptive network", color = Color.White)
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

        Text("Приложения", color = Color.White, fontWeight = FontWeight.SemiBold)
        Text(
            "Split tunnel: приложение работает без VPN. Auto-off: VPN отключается при запуске приложения.",
            color = Color(0xFF92A6B6),
            fontSize = 12.sp
        )

        state.installedApps.take(80).forEach { app ->
            val splitEnabled = state.splitTunnelApps.contains(app.packageName)
            val autoEnabled = state.autoDisconnectApps.contains(app.packageName)
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF071019)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = app.label,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = app.packageName,
                        color = Color(0xFF7F93A3),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = splitEnabled, onCheckedChange = { onToggleSplit(app.packageName) })
                        Text("Split tunnel", color = Color(0xFFC9D3DB))
                        Spacer(modifier = Modifier.width(10.dp))
                        Checkbox(checked = autoEnabled, onCheckedChange = { onToggleAuto(app.packageName) })
                        Text("Auto-off", color = Color(0xFFC9D3DB))
                    }
                }
            }
        }

        Text("Auth payload", color = Color.White, fontWeight = FontWeight.SemiBold)
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF08131D)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = state.deviceToken,
                    onValueChange = {},
                    label = { Text("Device token") },
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = true,
                    minLines = 1
                )
                OutlinedTextField(
                    value = state.hasActiveAccess,
                    onValueChange = {},
                    label = { Text("Has active access") },
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = true,
                    minLines = 1
                )
                OutlinedTextField(
                    value = state.activeSubscriptionLinks,
                    onValueChange = {},
                    label = { Text("Active subscription links") },
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = true,
                    minLines = 2
                )
                OutlinedTextField(
                    value = state.filteredSubscriptionLinks,
                    onValueChange = {},
                    label = { Text("Filtered subscriptions") },
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = true,
                    minLines = 2
                )
                OutlinedTextField(
                    value = state.selectedSubscriptionLink,
                    onValueChange = {},
                    label = { Text("Selected subscription link") },
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = true,
                    minLines = 1
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value > 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return "%.1f %s".format(value, units[unitIndex])
}

private const val AUTH_TOKEN_LENGTH = 32
private const val SIGN_UP_URL = "https://example.com/signup"
