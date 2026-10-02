package com.logicedge.opencodemobile.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.logicedge.opencodemobile.data.ChatPrefs
import com.logicedge.opencodemobile.data.ThemeMode
import com.logicedge.opencodemobile.data.UiPrefs
import com.logicedge.opencodemobile.notify.NotificationPrefs
import com.logicedge.opencodemobile.notify.NotifyPrefs
import com.logicedge.opencodemobile.server.LocalServerController
import com.logicedge.opencodemobile.server.LocalServerState
import com.logicedge.opencodemobile.ui.ConnectionViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    connectionViewModel: ConnectionViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notifyPrefsStore = remember { NotificationPrefs(context) }
    val chatPrefs = remember { ChatPrefs(context) }
    val uiPrefs = remember { UiPrefs(context) }
    val localServer = remember { LocalServerController(context) }

    var timeoutText by remember { mutableStateOf(connectionViewModel.healthTimeoutMs.toString()) }
    var pollText by remember { mutableStateOf(connectionViewModel.healthPollIntervalMs.toString()) }
    var exponential by remember { mutableStateOf(connectionViewModel.exponentialReconnectEnabled) }
    var notifyPrefs by remember { mutableStateOf(NotifyPrefs()) }
    var showThinking by remember { mutableStateOf(false) }
    var themeMode by remember { mutableStateOf(ThemeMode.SYSTEM) }
    var localState by remember { mutableStateOf<LocalServerState>(LocalServerState.Idle) }
    var zenKey by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        notifyPrefs = notifyPrefsStore.load()
        localState = localServer.refresh()
        showThinking = chatPrefs.showThinking.first()
        themeMode = uiPrefs.themeMode.first()
    }

    val notifyPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        scope.launch {
            notifyPrefsStore.save(
                NotifyPrefs(enabled = granted, settingsConfigured = true),
            )
            notifyPrefs = notifyPrefsStore.load()
        }
    }

    fun osNotifyGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsSection("Appearance") {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = themeMode == mode,
                            onClick = {
                                themeMode = mode
                                scope.launch { uiPrefs.setThemeMode(mode) }
                            },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = ThemeMode.entries.size,
                            ),
                        ) {
                            Text(
                                when (mode) {
                                    ThemeMode.SYSTEM -> "System"
                                    ThemeMode.LIGHT -> "Light"
                                    ThemeMode.DARK -> "Dark"
                                },
                            )
                        }
                    }
                }
            }

            SettingsSection("Connection") {
                OutlinedTextField(
                    value = timeoutText,
                    onValueChange = { input ->
                        timeoutText = input
                        input.toLongOrNull()?.let {
                            if (it in 1_000..30_000) connectionViewModel.healthTimeoutMs = it
                        }
                    },
                    label = { Text("Health check timeout (ms)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = pollText,
                    onValueChange = { input ->
                        pollText = input
                        input.toLongOrNull()?.let {
                            if (it in 2_000..60_000) connectionViewModel.healthPollIntervalMs = it
                        }
                    },
                    label = { Text("Health poll interval (ms)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                SettingRow(
                    label = "Automatic reconnect",
                    checked = exponential,
                    onCheckedChange = {
                        exponential = it
                        connectionViewModel.exponentialReconnectEnabled = it
                    },
                )
            }

            SettingsSection("Chat") {
                SettingRow(
                    label = "Show model thinking",
                    checked = showThinking,
                    onCheckedChange = { want ->
                        showThinking = want
                        scope.launch { chatPrefs.setShowThinking(want) }
                    },
                )
            }

            SettingsSection("Session notifications") {
                SettingRow(
                    label = "Notify when the agent replies",
                    checked = notifyPrefs.enabled,
                    onCheckedChange = { want ->
                        if (want && !osNotifyGranted()) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notifyPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        } else {
                            scope.launch {
                                notifyPrefsStore.save(
                                    notifyPrefs.copy(
                                        enabled = want,
                                        settingsConfigured = true,
                                    ),
                                )
                                notifyPrefs = notifyPrefsStore.load()
                            }
                        }
                    },
                )
            }

            SettingsSection("On-device server") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        val statusText = when (val s = localState) {
                            LocalServerState.Idle -> "Stopped"
                            LocalServerState.Running -> "Running (127.0.0.1:4096)"
                            is LocalServerState.Working -> s.step
                            is LocalServerState.Failed -> "Failed: ${s.message}"
                        }
                        Text(statusText, style = MaterialTheme.typography.bodyMedium)
                        if (!localServer.bootstrapAvailable()) {
                            Text(
                                "Bootstrap asset is not bundled with this build.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row {
                            Button(
                                onClick = {
                                    scope.launch {
                                        localState = LocalServerState.Working("Starting…")
                                        localServer.start()
                                        localState = localServer.refresh()
                                    }
                                },
                                enabled = localState is LocalServerState.Idle ||
                                    localState is LocalServerState.Failed,
                            ) { Text("Start") }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    scope.launch {
                                        localServer.stop()
                                        localState = localServer.refresh()
                                    }
                                },
                                enabled = localState is LocalServerState.Running,
                            ) { Text("Stop") }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = zenKey,
                            onValueChange = { zenKey = it },
                            label = { Text("Zen API key (optional)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    if (zenKey.isNotBlank()) {
                                        localServer.setZenApiKey(zenKey.trim())
                                        zenKey = ""
                                    }
                                }
                            },
                            enabled = zenKey.isNotBlank(),
                        ) { Text("Save API key") }
                    }
                }
            }

            SettingsSection("About") {
                Text(
                    "OpenCode Mobile ${appVersion()} connects to an already-running " +
                        "OpenCode server over LAN or VPN.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Source: github.com/logicedge/opencode-mobile-client",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun SettingRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun appVersion(): String {
    val context = LocalContext.current
    return remember {
        @Suppress("DEPRECATION")
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: ""
    }
}
