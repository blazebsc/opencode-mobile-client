package com.logicedge.opencodemobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.logicedge.opencodemobile.data.ServerStatus
import com.logicedge.opencodemobile.ui.ServerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LandingScreen(
    serverViewModel: ServerViewModel,
    onConnect: (String) -> Unit,
    onAddServer: () -> Unit,
    onManage: () -> Unit,
    onSettings: () -> Unit,
    onHelp: () -> Unit,
) {
    val profiles by serverViewModel.profiles.collectAsState()
    val loading by serverViewModel.loading.collectAsState()
    val default = profiles.firstOrNull { it.isDefault }
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        serverViewModel.load()
        serverViewModel.refreshStatuses()
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(HEALTH_POLL_MS)
            serverViewModel.refreshStatuses()
        }
    }

    fun refresh() {
        scope.launch {
            refreshing = true
            serverViewModel.refreshStatuses { refreshing = false }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OpenCode Mobile") },
                actions = {
                    IconButton(onClick = { serverViewModel.refreshStatuses() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (loading && profiles.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                return@Column
            }
            if (default == null) {
                Text("No servers yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Add your OpenCode server to get started.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onAddServer) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add Server")
                }
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        StatusRow(status = default.lastStatus)
                        Spacer(Modifier.height(8.dp))
                        Text(default.name, style = MaterialTheme.typography.titleLarge)
                        Text(
                            default.baseUrl,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        val meta = listOfNotNull(
                            "default",
                            "auth".takeIf { default.authEnabled },
                            default.serverVersion?.let { "${default.apiVersion.serial} · $it" },
                        ).joinToString("  ·  ")
                        if (meta.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                meta,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { onConnect(default.id) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (default.authEnabled) "Connect & Sign In" else "Connect")
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onAddServer) { Text("Add Another") }
                    OutlinedButton(onClick = onManage) { Text("Manage") }
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onHelp) { Text("Help") }
        }
        }
    }
}

private const val HEALTH_POLL_MS = 5_000L

@Composable
fun StatusRow(status: ServerStatus) {
    val label = when (status) {
        ServerStatus.CONNECTED -> "Connected"
        ServerStatus.CHECKING -> "Checking…"
        ServerStatus.AUTH_REQUIRED -> "Auth required"
        ServerStatus.WRONG_CREDENTIALS -> "Wrong credentials"
        ServerStatus.UNREACHABLE -> "Offline"
        ServerStatus.FRAME_BLOCKED -> "Blocked"
        ServerStatus.UNKNOWN -> "Unknown"
    }
    StatusDot(status = status)
    Spacer(Modifier.width(8.dp))
    Text(label, style = MaterialTheme.typography.bodyMedium)
}

@Composable
fun StatusDot(status: ServerStatus) {
    val color = when (status) {
        ServerStatus.CONNECTED -> MaterialTheme.colorScheme.primary
        ServerStatus.CHECKING -> MaterialTheme.colorScheme.outline
        ServerStatus.AUTH_REQUIRED -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.error
    }
    val alpha = if (status == ServerStatus.CHECKING) {
        val transition = rememberInfiniteTransition(label = "statusPulse")
        val pulse by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(700, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "statusAlpha",
        )
        pulse
    } else {
        1f
    }
    androidx.compose.foundation.Canvas(modifier = Modifier.size(10.dp)) {
        drawCircle(color = color, alpha = alpha)
    }
}
