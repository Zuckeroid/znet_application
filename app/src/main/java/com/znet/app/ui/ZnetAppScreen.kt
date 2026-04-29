package com.znet.app.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.text.style.TextAlign
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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

private enum class BottomTab {
    Home,
    Servers,
    Settings
}

private enum class SettingsSection(val title: String) {
    Connection("Доступ"),
    Apps("Приложения"),
    Permissions("Разрешения"),
    Diagnostics("Диагностика")
}

private enum class PolicyListKind(val title: String, val description: String) {
    Routing(
        title = "Маршрутизация",
        description = "Какие установленные приложения идут через VPN."
    ),
    AutoOn(
        title = "Auto ON",
        description = "Какие установленные приложения могут автоматически поднимать туннель."
    ),
    AutoOff(
        title = "Auto OFF",
        description = "Какие установленные приложения временно выключают VPN."
    )
}

private data class ChoiceChipItem(
    val value: String,
    val label: String
)

private val NeonGreen = Color(0xFF5BFF74)
private val NeonGreenSoft = Color(0x665BFF74)
private val NeonMuted = Color(0xFF6D7D8A)
private val NeonButtonOutline = Color(0xFF48FF70)
private val NeonButtonBg = Color(0xFF0A2413)
private val ControlBorderWidth = 1.dp

@Composable
private fun znetScreenBackgroundColor(): Color = MaterialTheme.colorScheme.background

@Composable
private fun znetCardColors() = CardDefaults.cardColors(
    containerColor = MaterialTheme.colorScheme.surface
)

@Composable
private fun znetPrimaryTextColor(): Color = MaterialTheme.colorScheme.onSurface

@Composable
private fun znetSecondaryTextColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant

@Composable
private fun znetMutedSurfaceColor(): Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.74f)

@Composable
private fun znetMutedBorderColor(): Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.42f)

@Composable
private fun ZnetSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = NeonGreen,
            checkedTrackColor = NeonButtonBg,
            checkedBorderColor = NeonButtonOutline,
            uncheckedThumbColor = Color(0xFF92A6B6),
            uncheckedTrackColor = Color(0xFF0B1117),
            uncheckedBorderColor = Color(0xFF42515B),
            disabledCheckedThumbColor = Color(0xFF597064),
            disabledCheckedTrackColor = Color(0xFF112019),
            disabledCheckedBorderColor = Color(0xFF2F4A39),
            disabledUncheckedThumbColor = Color(0xFF59646B),
            disabledUncheckedTrackColor = Color(0xFF0B1117),
            disabledUncheckedBorderColor = Color(0xFF26323B)
        )
    )
}

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
            scope.launch { snackbarHostState.showSnackbar("Нужно разрешить VPN-подключение") }
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
                    onThemeModeChanged = viewModel::setThemeMode,
                    onLanguageChanged = viewModel::setLanguageCode,
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
                    onOpenBatterySettings = { openBatteryOptimizationSettings(context) },
                    onOpenAutostartSettings = { openAutostartSettings(context) },
                    onOpenAppInfoSettings = { openAppInfoSettings(context) },
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
                    border = BorderStroke(ControlBorderWidth, NeonButtonOutline)
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
    modifier: Modifier = Modifier
) {
    val connected = state.connectionState == ConnectionState.CONNECTED
    val statusTitle = when (state.connectionState) {
        ConnectionState.CONNECTED -> "Znet подключен"
        ConnectionState.CONNECTING -> "Znet подключается"
        ConnectionState.PAUSED_BY_RULE -> "Znet на паузе"
        ConnectionState.ERROR -> "Znet не подключен"
        ConnectionState.DISCONNECTED -> "Znet отключен"
    }
    val statusText = when (state.connectionState) {
        ConnectionState.CONNECTED -> "Соединение защищено"
        ConnectionState.CONNECTING -> "Готовим защищенное подключение"
        ConnectionState.PAUSED_BY_RULE -> "Отключено правилом Auto OFF"
        ConnectionState.ERROR -> state.message ?: "Проверьте подключение"
        ConnectionState.DISCONNECTED -> "Нажмите для подключения"
    }

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
                val serverLabel = if (node == null) {
                    buildAnnotatedString {
                        append("Сервер не выбран")
                    }
                } else {
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = Color(0xFF8EA2B2))) {
                            append("Сервер:")
                        }
                        append(" ${node.flagEmoji} ${node.name}")
                    }
                }
                Text(
                    text = serverLabel,
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
                Text(text = "Подключение", color = Color(0xFF8EA2B2))
                Spacer(modifier = Modifier.height(6.dp))
                InfoRow(
                    label = "Протокол",
                    value = formatProtocolTransport(state.protocol, state.transport)
                )
                InfoRow(
                    label = "Подписка",
                    value = state.serviceTitle.orEmpty().ifBlank { "не определена" }
                )
                InfoRow(
                    label = "Осталось",
                    value = formatDaysRemaining(state.serviceDaysRemaining),
                    caption = formatExpiryCaption(state.serviceExpiresAt)
                )
            }
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    caption: String? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = if (caption.isNullOrBlank()) {
            Alignment.CenterVertically
        } else {
            Alignment.Top
        }
    ) {
        Text(
            text = label,
            color = Color(0xFF90A4B5)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = value,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!caption.isNullOrBlank()) {
                Text(
                    text = caption,
                    color = Color(0xFF90A4B5),
                    fontSize = 12.sp,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
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
            .background(znetScreenBackgroundColor())
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Доступ", color = znetPrimaryTextColor(), style = MaterialTheme.typography.titleLarge)
            Button(
                onClick = onRefresh,
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonButtonBg,
                    contentColor = NeonGreen
                ),
                border = BorderStroke(ControlBorderWidth, NeonButtonOutline)
            ) {
                Text("Обновить доступ")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (state.nodes.isEmpty()) {
            Text("Доступ еще не получен", color = znetSecondaryTextColor())
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
    val borderColor = if (selected) Color(0xFF4BFF68) else znetMutedBorderColor()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        colors = znetCardColors(),
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
                Text(node.name, color = znetPrimaryTextColor(), fontWeight = FontWeight.Medium)
            }
            if (selected) {
                Text("Выбран", color = Color(0xFF4BFF68), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SettingsSectionSelector(
    selected: SettingsSection,
    onSelected: (SettingsSection) -> Unit
) {
    val sections = remember {
        listOf(
            SettingsSection.Connection,
            SettingsSection.Apps,
            SettingsSection.Permissions,
            SettingsSection.Diagnostics
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        sections.forEach { section ->
            val active = section == selected
            Card(
                modifier = Modifier
                    .widthIn(min = 118.dp)
                    .clickable { onSelected(section) },
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (active) NeonButtonBg else znetMutedSurfaceColor(),
                ),
                border = BorderStroke(
                    width = ControlBorderWidth,
                    color = if (active) NeonButtonOutline else znetMutedBorderColor()
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = section.title,
                        color = if (active) NeonGreen else znetSecondaryTextColor(),
                        fontSize = 13.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1
                    )
                }
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
    onThemeModeChanged: (String) -> Unit,
    onLanguageChanged: (String) -> Unit,
    onResetPolicies: () -> Unit,
    onResetZnetVpn: () -> Unit,
    onOpenVpnSettings: () -> Unit,
    onOpenUsageSettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenAutostartSettings: () -> Unit,
    onOpenAppInfoSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val recommendedRoutingApps = recommendedRoutingPackages(state)
    val recommendedAutoOnApps = state.automationPolicy.autoConnectApps
    val recommendedAutoOffApps = state.automationPolicy.autoDisconnectApps
    var activePolicyListName by rememberSaveable { mutableStateOf<String?>(null) }
    val activePolicyList = activePolicyListName?.let { name ->
        runCatching { PolicyListKind.valueOf(name) }.getOrNull()
    }
    var selectedSettingsSectionName by rememberSaveable {
        mutableStateOf(SettingsSection.Connection.name)
    }
    val selectedSettingsSection = runCatching {
        SettingsSection.valueOf(selectedSettingsSectionName)
    }.getOrDefault(SettingsSection.Connection)
    val znetVpnActive = state.connectionState == ConnectionState.CONNECTED ||
        state.connectionState == ConnectionState.CONNECTING
    val vpnDiagnosticTitle = when {
        znetVpnActive -> "Активен Znet"
        state.vpnTransportActive -> "Активен другой VPN"
        else -> "Активный VPN не найден"
    }
    val vpnDiagnosticText = when {
        znetVpnActive -> "Туннелем управляет Znet."
        state.vpnTransportActive -> "Android не раскрывает владельца VPN приложению. Откройте системные настройки, чтобы отключить другой профиль."
        else -> "Можно подключать Znet. Системная запись VPN без активного туннеля здесь не учитывается."
    }

    if (activePolicyList != null) {
        val selectedPackages = when (activePolicyList) {
            PolicyListKind.Routing -> state.routingApps
            PolicyListKind.AutoOn -> state.autoConnectApps
            PolicyListKind.AutoOff -> state.autoDisconnectApps
        }
        val recommendedPackages = when (activePolicyList) {
            PolicyListKind.Routing -> recommendedRoutingApps
            PolicyListKind.AutoOn -> recommendedAutoOnApps
            PolicyListKind.AutoOff -> recommendedAutoOffApps
        }
        val enabled = when (activePolicyList) {
            PolicyListKind.Routing -> state.routingEnabled
            PolicyListKind.AutoOn -> state.autoConnectEnabled
            PolicyListKind.AutoOff -> state.autoDisconnectEnabled
        }
        val onTogglePackage = when (activePolicyList) {
            PolicyListKind.Routing -> onToggleRouting
            PolicyListKind.AutoOn -> onToggleAutoConnect
            PolicyListKind.AutoOff -> onToggleAutoDisconnect
        }

        AppPolicyListScreen(
            kind = activePolicyList,
            enabled = enabled,
            selectedPackages = selectedPackages,
            recommendedPackages = recommendedPackages,
            apps = state.installedApps,
            onTogglePackage = onTogglePackage,
            onBack = { activePolicyListName = null },
            modifier = modifier
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(znetScreenBackgroundColor())
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Настройки", color = znetPrimaryTextColor(), style = MaterialTheme.typography.titleLarge)
        SettingsSectionSelector(
            selected = selectedSettingsSection,
            onSelected = { section -> selectedSettingsSectionName = section.name }
        )

        when (selectedSettingsSection) {
            SettingsSection.Connection -> {
                Card(
                    colors = znetCardColors(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Подключение", color = znetPrimaryTextColor(), fontWeight = FontWeight.SemiBold)
                        Text(
                            "Приложение получает доступ через токен из личного кабинета. Добавление устройств и управление ими происходит на стороне биллинга.",
                            color = znetSecondaryTextColor(),
                            fontSize = 12.sp
                        )
                    }
                }

                Card(
                    colors = znetCardColors(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Оформление", color = znetPrimaryTextColor(), fontWeight = FontWeight.SemiBold)
                        ChoiceRow(
                            title = "Тема",
                            selectedValue = state.themeMode,
                            choices = listOf(
                                ChoiceChipItem("system", "Системная"),
                                ChoiceChipItem("dark", "Темная"),
                                ChoiceChipItem("light", "Светлая")
                            ),
                            onSelected = onThemeModeChanged
                        )
                        ChoiceRow(
                            title = "Язык",
                            selectedValue = state.languageCode,
                            choices = listOf(
                                ChoiceChipItem("ru", "🇷🇺 RU"),
                                ChoiceChipItem("en", "🇺🇸 US")
                            ),
                            onSelected = onLanguageChanged
                        )
                    }
                }

                Card(
                    colors = znetCardColors(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Сервер доступа", color = znetPrimaryTextColor(), fontWeight = FontWeight.SemiBold)
                        Text(
                            "Доступ: ${state.apiBaseUrl.ifBlank { "не выбран" }}",
                            color = znetSecondaryTextColor(),
                            fontSize = 12.sp
                        )
                        Text(
                            "Личный кабинет: ${state.webBaseUrl}",
                            color = znetSecondaryTextColor(),
                            fontSize = 12.sp
                        )
                        Text(
                            "Пул доменов: доступ ${state.apiDomainCount}, сайт ${state.webDomainCount}${formatDomainRevision(state.domainBundleRevision)}",
                            color = znetSecondaryTextColor(),
                            fontSize = 12.sp
                        )
                    }
                }

                Card(
                    colors = znetCardColors(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("За границей", color = znetPrimaryTextColor(), fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Российские приложения из Auto OFF будут идти через московскую ноду.",
                                    color = znetSecondaryTextColor(),
                                    fontSize = 12.sp
                                )
                            }
                            ZnetSwitch(
                                checked = state.awayModeEnabled,
                                enabled = state.awayModeAvailable,
                                onCheckedChange = onAwayModeChanged
                            )
                        }
                        if (!state.awayModeAvailable) {
                            Text(
                                "Нужна активная московская нода для режима «За границей».",
                                color = Color(0xFFFFC857),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            SettingsSection.Apps -> {
                Card(
                    colors = znetCardColors(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Адаптивная сеть", color = znetPrimaryTextColor(), fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Znet будет автоматически применять актуальные настройки подключения.",
                                    color = znetSecondaryTextColor(),
                                    fontSize = 12.sp
                                )
                            }
                            ZnetSwitch(
                                checked = state.adaptiveEnabled,
                                onCheckedChange = onAdaptiveChanged
                            )
                        }
                    }
                }

                Card(
                    colors = znetCardColors(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Auto ON/OFF", color = znetPrimaryTextColor(), fontWeight = FontWeight.SemiBold)
                        Text(
                            "Для автоматического включения и выключения туннеля нужен доступ к активности приложений.",
                            color = znetSecondaryTextColor(),
                            fontSize = 12.sp
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onOpenUsageSettings)
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Доступ к активности",
                                    color = znetPrimaryTextColor(),
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    if (state.usageAccessGranted) {
                                        "Включено"
                                    } else {
                                        "Выключено. Нажмите, чтобы открыть настройки Android."
                                    },
                                    color = if (state.usageAccessGranted) NeonGreen else Color(0xFFFFC857),
                                    fontSize = 12.sp
                                )
                            }
                            ZnetSwitch(
                                checked = state.usageAccessGranted,
                                onCheckedChange = { onOpenUsageSettings() }
                            )
                        }
                    }
                }

                Card(
                    colors = znetCardColors(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Правила приложений", color = znetPrimaryTextColor(), fontWeight = FontWeight.SemiBold)
                        Text(
                            "Рекомендации пришли с сервера. Дальше это локальные настройки этого телефона.",
                            color = znetSecondaryTextColor(),
                            fontSize = 12.sp
                        )
                        Text(
                            "Маршрутизация: ${formatRoutingPolicy(state)}",
                            color = znetSecondaryTextColor(),
                            fontSize = 12.sp
                        )
                        Text(
                            "Auto ON: ${recommendedAutoOnApps.size} рекомендовано, Auto OFF: ${recommendedAutoOffApps.size} рекомендовано",
                            color = znetSecondaryTextColor(),
                            fontSize = 12.sp
                        )
                        Button(
                            onClick = onResetPolicies,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NeonButtonBg,
                                contentColor = NeonGreen
                            ),
                            border = BorderStroke(ControlBorderWidth, NeonButtonOutline)
                        ) {
                            Text("Сбросить к рекомендованным")
                        }
                    }
                }

                AppPolicySection(
                    title = PolicyListKind.Routing.title,
                    description = "Какие приложения идут через VPN. Если секция выключена, туннель работает для всех приложений.",
                    enabled = state.routingEnabled,
                    selectedPackages = state.routingApps,
                    recommendedPackages = recommendedRoutingApps,
                    apps = state.installedApps,
                    onEnabledChanged = onRoutingEnabledChanged,
                    onOpenList = { activePolicyListName = PolicyListKind.Routing.name }
                )

                AppPolicySection(
                    title = PolicyListKind.AutoOn.title,
                    description = "Какие приложения могут автоматически поднимать туннель. Для этого нужен доступ к активности.",
                    enabled = state.autoConnectEnabled,
                    selectedPackages = state.autoConnectApps,
                    recommendedPackages = recommendedAutoOnApps,
                    apps = state.installedApps,
                    onEnabledChanged = onAutoConnectEnabledChanged,
                    onOpenList = { activePolicyListName = PolicyListKind.AutoOn.name }
                )

                AppPolicySection(
                    title = PolicyListKind.AutoOff.title,
                    description = "Какие приложения временно выключают VPN. Этот список имеет приоритет над Auto ON.",
                    enabled = state.autoDisconnectEnabled,
                    selectedPackages = state.autoDisconnectApps,
                    recommendedPackages = recommendedAutoOffApps,
                    apps = state.installedApps,
                    onEnabledChanged = onAutoDisconnectEnabledChanged,
                    onOpenList = { activePolicyListName = PolicyListKind.AutoOff.name }
                )
            }

            SettingsSection.Permissions -> {
                PermissionStatusCard(
                    title = "VPN-подключение",
                    description = "Разрешение Android на создание защищенного туннеля.",
                    status = if (state.vpnPermissionGranted) "Разрешено" else "Требуется разрешение",
                    granted = state.vpnPermissionGranted,
                    actionText = "Настроить VPN",
                    onClick = onOpenVpnSettings
                )

                PermissionStatusCard(
                    title = "Уведомления",
                    description = "Нужны для карточки активного VPN и службы Auto ON/OFF.",
                    status = if (state.notificationsEnabled) "Включены" else "Выключены",
                    granted = state.notificationsEnabled,
                    actionText = "Открыть уведомления",
                    onClick = onOpenNotificationSettings
                )

                PermissionStatusCard(
                    title = "Доступ к активности",
                    description = "Позволяет Auto ON/OFF видеть, какое приложение открыто сейчас.",
                    status = if (state.usageAccessGranted) "Включен" else "Выключен",
                    granted = state.usageAccessGranted,
                    actionText = "Открыть доступ",
                    onClick = onOpenUsageSettings
                )

                PermissionStatusCard(
                    title = "Работа в фоне",
                    description = "Помогает Android не останавливать Znet и Auto ON/OFF при заблокированном экране.",
                    status = if (state.batteryOptimizationIgnored) "Без ограничений" else "Может быть ограничено",
                    granted = state.batteryOptimizationIgnored,
                    actionText = "Разрешить фон",
                    onClick = onOpenBatterySettings
                )

                PermissionStatusCard(
                    title = "Автозапуск",
                    description = "На Xiaomi, Huawei, Honor, OPPO, Realme и Vivo это отдельная настройка производителя.",
                    status = "Проверьте вручную",
                    granted = null,
                    actionText = "Открыть автозапуск",
                    onClick = onOpenAutostartSettings
                )

                PermissionStatusCard(
                    title = "Ограниченные настройки",
                    description = "Если APK установлен вручную и Android не дает включить доступ к активности: карточка Znet → три точки → Разрешить ограниченные настройки.",
                    status = "Только для APK",
                    granted = null,
                    actionText = "Открыть карточку Znet",
                    onClick = onOpenAppInfoSettings
                )
            }

            SettingsSection.Diagnostics -> {
                Card(
                    colors = znetCardColors(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Уведомления", color = znetPrimaryTextColor(), fontWeight = FontWeight.SemiBold)
                        Text(
                            if (state.notificationsEnabled) {
                                "Znet может показывать активный VPN и автоматику в шторке."
                            } else {
                                "Уведомления выключены, поэтому сервис работает без карточки в шторке."
                            },
                            color = if (state.notificationsEnabled) znetSecondaryTextColor() else Color(0xFFFFC857),
                            fontSize = 12.sp
                        )
                        Button(
                            onClick = onOpenNotificationSettings,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NeonButtonBg,
                                contentColor = NeonGreen
                            ),
                            border = BorderStroke(ControlBorderWidth, NeonButtonOutline)
                        ) {
                            Text("Настройки уведомлений")
                        }
                    }
                }

                Card(
                    colors = znetCardColors(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("VPN диагностика", color = znetPrimaryTextColor(), fontWeight = FontWeight.SemiBold)
                        Text(vpnDiagnosticTitle, color = if (state.vpnTransportActive || znetVpnActive) NeonGreen else znetPrimaryTextColor())
                        Text(vpnDiagnosticText, color = znetSecondaryTextColor(), fontSize = 12.sp)
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
                                border = BorderStroke(ControlBorderWidth, NeonButtonOutline)
                            ) {
                                Text("Сбросить Znet", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Button(
                                onClick = onOpenVpnSettings,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = znetMutedSurfaceColor(),
                                    contentColor = znetPrimaryTextColor()
                                ),
                                border = BorderStroke(ControlBorderWidth, znetMutedBorderColor())
                            ) {
                                Text("Настройки VPN", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(
    title: String,
    selectedValue: String,
    choices: List<ChoiceChipItem>,
    onSelected: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = znetSecondaryTextColor(), fontSize = 12.sp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            choices.forEach { choice ->
                val selected = selectedValue == choice.value
                Card(
                    modifier = Modifier
                        .widthIn(min = 96.dp)
                        .clickable { onSelected(choice.value) },
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) NeonButtonBg else znetMutedSurfaceColor()
                    ),
                    border = BorderStroke(
                        ControlBorderWidth,
                        if (selected) NeonButtonOutline else znetMutedBorderColor()
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            choice.label,
                            color = if (selected) NeonGreen else znetSecondaryTextColor(),
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionStatusCard(
    title: String,
    description: String,
    status: String,
    granted: Boolean?,
    actionText: String,
    onClick: () -> Unit
) {
    val statusColor = when (granted) {
        true -> NeonGreen
        false -> Color(0xFFFFC857)
        null -> znetSecondaryTextColor()
    }

    Card(
        colors = znetCardColors(),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, color = znetPrimaryTextColor(), fontWeight = FontWeight.SemiBold)
                    Text(description, color = znetSecondaryTextColor(), fontSize = 12.sp)
                }
                Text(
                    status,
                    color = statusColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonButtonBg,
                    contentColor = NeonGreen
                ),
                border = BorderStroke(ControlBorderWidth, NeonButtonOutline)
            ) {
                Text(actionText)
            }
        }
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
    onOpenList: () -> Unit
) {
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
    val installedSelectedCount = installedItems.count { item -> normalizedSelected.contains(item.packageName) }
    val installedRecommendedCount = installedItems.count { item -> normalizedRecommended.contains(item.packageName) }

    Card(
        colors = znetCardColors(),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, color = znetPrimaryTextColor(), fontWeight = FontWeight.SemiBold)
                    Text(
                        "$installedSelectedCount выбрано / $installedRecommendedCount рекомендовано / ${installedItems.size} установлено",
                        color = znetSecondaryTextColor(),
                        fontSize = 12.sp
                    )
                }
                ZnetSwitch(checked = enabled, onCheckedChange = onEnabledChanged)
            }
            Text(description, color = znetSecondaryTextColor(), fontSize = 12.sp)

            Button(
                onClick = onOpenList,
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonButtonBg,
                    contentColor = NeonGreen
                ),
                border = BorderStroke(ControlBorderWidth, NeonButtonOutline)
            ) {
                Text("Открыть список (${installedItems.size})")
            }
        }
    }
}

@Composable
private fun AppPolicyListScreen(
    kind: PolicyListKind,
    enabled: Boolean,
    selectedPackages: Set<String>,
    recommendedPackages: Set<String>,
    apps: List<InstalledApp>,
    onTogglePackage: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var query by rememberSaveable(kind.name) { mutableStateOf("") }
    val normalizedSelected = remember(selectedPackages) { selectedPackages.normalizedPackageSet() }
    val normalizedRecommended = remember(recommendedPackages) { recommendedPackages.normalizedPackageSet() }
    val items = remember(apps, normalizedSelected, normalizedRecommended, query) {
        apps.map { app ->
            PolicyAppItem(
                packageName = app.packageName,
                label = app.label,
                installed = true
            )
        }
            .filterByPolicyQuery(query)
            .sortedWith(
                compareByDescending<PolicyAppItem> { item -> normalizedSelected.contains(item.packageName) }
                    .thenByDescending { item -> normalizedRecommended.contains(item.packageName) }
                    .thenBy { item -> item.label.lowercase() }
                    .thenBy { item -> item.packageName }
            )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(znetScreenBackgroundColor())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onBack,
                colors = ButtonDefaults.buttonColors(
                    containerColor = znetMutedSurfaceColor(),
                    contentColor = znetPrimaryTextColor()
                ),
                border = BorderStroke(ControlBorderWidth, znetMutedBorderColor())
            ) {
                Text("Назад")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(kind.title, color = znetPrimaryTextColor(), style = MaterialTheme.typography.titleLarge)
                Text(kind.description, color = znetSecondaryTextColor(), fontSize = 12.sp)
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Поиск приложения") }
        )

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            if (items.isEmpty()) {
                item {
                    Text(
                        "Установленные приложения не найдены.",
                        color = znetSecondaryTextColor(),
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
            }

            items(items, key = { item -> item.packageName }) { app ->
                PolicyAppRow(
                    app = app,
                    enabled = enabled,
                    selected = normalizedSelected.contains(app.packageName),
                    recommended = normalizedRecommended.contains(app.packageName),
                    onTogglePackage = onTogglePackage
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
                recommended -> Color(0xFF071A11)
                else -> znetMutedSurfaceColor()
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
            PolicyCheckbox(
                checked = selected,
                enabled = enabled,
                onClick = { onTogglePackage(app.packageName) }
            )
            Spacer(modifier = Modifier.width(8.dp))
            PolicyAppIcon(app = app)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    color = znetPrimaryTextColor(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun PolicyCheckbox(
    checked: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val borderColor = when {
        !enabled -> Color(0xFF26323B)
        checked -> NeonButtonOutline
        else -> znetMutedBorderColor()
    }
    val backgroundColor = when {
        !enabled -> Color(0xFF0B1117)
        checked -> NeonButtonBg
        else -> Color.Transparent
    }

    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(backgroundColor)
            .border(ControlBorderWidth, borderColor, RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = NeonGreen,
                modifier = Modifier.size(16.dp)
            )
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
            .background(znetMutedSurfaceColor()),
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
                color = znetPrimaryTextColor(),
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
private val ExpiryDateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yy HH:mm")
private val ExpiryDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yy")
private val ServerDateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private val ServerDateTimeShortFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

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

private fun formatDaysRemaining(daysRemaining: Int?): String {
    return when (daysRemaining) {
        null -> "неизвестно"
        1 -> "1 день"
        in 2..4 -> "$daysRemaining дня"
        else -> "$daysRemaining дней"
    }
}

private fun formatExpiryCaption(expiresAt: String?): String? {
    val datePart = formatExpiryDate(expiresAt) ?: return null
    return "до $datePart"
}

private fun formatProtocolTransport(
    protocol: String?,
    transport: String?
): String {
    val cleanProtocol = protocol?.trim()?.takeIf { it.isNotBlank() }?.uppercase()
    val cleanTransport = transport?.trim()?.takeIf { it.isNotBlank() }?.uppercase()
    return when {
        cleanProtocol != null && cleanTransport != null -> "$cleanProtocol/$cleanTransport"
        cleanProtocol != null -> cleanProtocol
        cleanTransport != null -> cleanTransport
        else -> "не определен"
    }
}

private fun formatExpiryDate(rawValue: String?): String? {
    val value = rawValue?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return runCatching {
        OffsetDateTime.parse(value).format(ExpiryDateTimeFormatter)
    }.recoverCatching {
        LocalDateTime.parse(value).format(ExpiryDateTimeFormatter)
    }.recoverCatching {
        LocalDateTime.parse(value, ServerDateTimeFormatter).format(ExpiryDateTimeFormatter)
    }.recoverCatching {
        LocalDateTime.parse(value, ServerDateTimeShortFormatter).format(ExpiryDateTimeFormatter)
    }.recoverCatching {
        LocalDate.parse(value.substringBefore('T').substringBefore(' ')).format(ExpiryDateFormatter)
    }.getOrNull()
}

private fun formatDomainRevision(revision: String?): String {
    val cleanRevision = revision?.trim()?.takeIf { it.isNotBlank() } ?: return ""
    return ", версия $cleanRevision"
}

private fun openBatteryOptimizationSettings(context: Context) {
    val intents = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            add(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}")
                )
            )
            add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        add(appInfoIntent(context))
    }
    startFirstResolvableActivity(context, intents)
}

private fun openAutostartSettings(context: Context) {
    val intents = mutableListOf<Intent>()
    val manufacturer = Build.MANUFACTURER.lowercase()

    fun addComponent(packageName: String, className: String) {
        intents += Intent().setComponent(ComponentName(packageName, className))
    }

    if ("xiaomi" in manufacturer || "redmi" in manufacturer || "poco" in manufacturer) {
        addComponent("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
    }
    if ("huawei" in manufacturer || "honor" in manufacturer) {
        addComponent("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
        addComponent("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
    }
    if ("oppo" in manufacturer || "realme" in manufacturer || "oneplus" in manufacturer) {
        addComponent("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
        addComponent("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity")
    }
    if ("vivo" in manufacturer || "iqoo" in manufacturer) {
        addComponent("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
        addComponent("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
    }

    intents += appInfoIntent(context)
    startFirstResolvableActivity(context, intents)
}

private fun openAppInfoSettings(context: Context) {
    startFirstResolvableActivity(context, listOf(appInfoIntent(context)))
}

private fun appInfoIntent(context: Context): Intent {
    return Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}")
    )
}

private fun startFirstResolvableActivity(
    context: Context,
    intents: List<Intent>
) {
    val packageManager = context.packageManager
    val intent = intents.firstOrNull { candidate ->
        candidate.resolveActivity(packageManager) != null
    } ?: Intent(Settings.ACTION_SETTINGS)

    context.startActivity(intent.apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}
