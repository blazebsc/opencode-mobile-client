package com.logicedge.opencodemobile.ui.screens

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.logicedge.opencodemobile.data.DemoMode
import com.logicedge.opencodemobile.data.Pairing
import com.logicedge.opencodemobile.data.ServerProfile
import com.logicedge.opencodemobile.data.ServerRepository
import com.logicedge.opencodemobile.data.ServerStatus
import com.logicedge.opencodemobile.data.UrlUtils
import com.logicedge.opencodemobile.ui.ServerViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerFormScreen(
    repository: ServerRepository,
    serverViewModel: ServerViewModel,
    profileId: String?,
    onDone: () -> Unit,
    onBack: () -> Unit,
    onScan: () -> Unit = {},
    qrFromScan: String? = null,
    onScanConsumed: () -> Unit = {},
) {
    val profiles by serverViewModel.profiles.collectAsState()
    val scope = rememberCoroutineScope()
    val existing = profiles.firstOrNull { it.id == profileId }

    // rememberSaveable: survives navigating to the QR scanner and back.
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var baseUrl by rememberSaveable { mutableStateOf(existing?.baseUrl ?: "") }
    var authEnabled by rememberSaveable { mutableStateOf(existing?.authEnabled ?: false) }
    var username by rememberSaveable { mutableStateOf(existing?.username ?: "opencode") }
    // Credentials stay in plain remember: rememberSaveable would persist them
    // to the saved-instance-state Bundle in plaintext.
    var password by remember { mutableStateOf("") }
    var isDefault by rememberSaveable { mutableStateOf(existing?.isDefault ?: false) }
    var allowInsecureHttp by rememberSaveable { mutableStateOf(existing?.allowInsecureHttp ?: true) }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var urlError by remember { mutableStateOf<String?>(null) }
    var urlWarning by remember { mutableStateOf<String?>(null) }
    var loadedPassword by rememberSaveable { mutableStateOf(false) }
    var showPairDialog by rememberSaveable { mutableStateOf(false) }
    var pairLink by remember { mutableStateOf("") }
    var pairError by remember { mutableStateOf<String?>(null) }
    var pairing by remember { mutableStateOf(false) }

    LaunchedEffect(profileId) {
        if (profileId != null && !loadedPassword) {
            loadedPassword = true
            password = repository.getPassword(profileId) ?: ""
        }
    }

    LaunchedEffect(qrFromScan) {
        if (!qrFromScan.isNullOrBlank()) {
            pairLink = qrFromScan
            pairError = null
            showPairDialog = true
            onScanConsumed()
        }
    }

    fun validateLive() {
        if (DemoMode.isDemoCredentials(username.ifEmpty { null }, password.ifEmpty { null })) {
            urlError = null
            urlWarning = null
            return
        }
        if (!allowInsecureHttp && UrlUtils.isPublicHttp(baseUrl)) {
            urlError = "Insecure HTTP is disabled for this server. Use HTTPS, a VPN, or allow insecure HTTP below."
            urlWarning = null
            return
        }
        val result = UrlUtils.validateUrl(baseUrl)
        urlError = if (!result.valid) result.error else null
        urlWarning = if (result.valid) result.error else null
    }

    if (showPairDialog) {
        AlertDialog(
            onDismissRequest = { if (!pairing) showPairDialog = false },
            title = { Text("Pair with server") },
            text = {
                Column {
                    Text(
                        "On the server, run \"opencode pair\", then scan the QR code it prints " +
                            "or paste the link here. Works with v2 servers.",
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pairLink,
                        onValueChange = {
                            pairLink = it
                            pairError = null
                        },
                        label = { Text("Pairing link") },
                        placeholder = { Text("http://192.168.1.10:4096/auth/connect/…") },
                        isError = pairError != null,
                        supportingText = pairError?.let { { Text(it) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val parsed = Pairing.parseLink(pairLink)
                        if (parsed == null) {
                            pairError = "That doesn't look like a pairing link"
                            return@TextButton
                        }
                        pairing = true
                        scope.launch {
                            val result = Pairing.redeem(parsed.first, parsed.second)
                            pairing = false
                            when (result) {
                                is Pairing.RedeemResult.Token -> {
                                    baseUrl = parsed.first
                                    username = "opencode"
                                    password = result.token
                                    authEnabled = true
                                    validateLive()
                                    showPairDialog = false
                                }
                                Pairing.RedeemResult.Unreachable -> pairError =
                                    "Couldn't reach ${parsed.first}. Keep \"opencode pair\" " +
                                        "running on the server and make sure this device is " +
                                        "on the same network."
                                Pairing.RedeemResult.Rejected -> pairError =
                                    "Pairing rejected — codes expire after 5 minutes and " +
                                        "work once. Run \"opencode pair\" again and rescan."
                            }
                        }
                    },
                    enabled = !pairing,
                ) { Text(if (pairing) "Pairing…" else "Pair") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = onScan, enabled = !pairing) {
                        Text("Scan QR code")
                    }
                    TextButton(onClick = { showPairDialog = false }, enabled = !pairing) {
                        Text("Cancel")
                    }
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (profileId == null) "Add Server" else "Edit Server") },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = { showPairDialog = true }) {
                Text("Pair with code/link instead")
            }
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    nameError = null
                },
                label = { Text("Name") },
                isError = nameError != null,
                supportingText = nameError?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = {
                    baseUrl = it
                    validateLive()
                },
                label = { Text("Server URL") },
                placeholder = { Text("http://192.168.1.10:4096") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                isError = urlError != null,
                supportingText = {
                    (urlError ?: urlWarning)?.let { Text(it) }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Authentication", modifier = Modifier.weight(1f))
                Switch(checked = authEnabled, onCheckedChange = { authEnabled = it })
            }
            if (authEnabled) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        validateLive()
                    },
                    label = { Text("Password") },
                    visualTransformation = if (showPassword) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row {
                    TextButtonLike("Show password: ${if (showPassword) "on" else "off"}") {
                        showPassword = !showPassword
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButtonLike("Clear") { password = "" }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Default server", modifier = Modifier.weight(1f))
                Switch(checked = isDefault, onCheckedChange = { isDefault = it })
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Allow insecure HTTP", modifier = Modifier.weight(1f))
                Switch(
                    checked = allowInsecureHttp,
                    onCheckedChange = {
                        allowInsecureHttp = it
                        validateLive()
                    },
                )
            }
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    val trimmedName = name.trim()
                    if (trimmedName.isEmpty()) {
                        nameError = "Name is required"
                        return@Button
                    }
                    val demo = DemoMode.isDemoCredentials(
                        username.ifEmpty { null },
                        password.ifEmpty { null },
                    )
                    val finalUrl = if (demo) baseUrl.trim() else UrlUtils.normalizeUrl(baseUrl)
                    if (!demo) {
                        if (!allowInsecureHttp && UrlUtils.isPublicHttp(finalUrl)) {
                            urlError = "Insecure HTTP is disabled for this server. Use HTTPS, a VPN, or allow insecure HTTP below."
                            return@Button
                        }
                        val result = UrlUtils.validateUrl(finalUrl)
                        if (!result.valid) {
                            urlError = result.error
                            return@Button
                        }
                    }
                    scope.launch {
                        if (profileId == null) {
                            val created = repository.createProfile(
                                ServerProfile(
                                    id = "",
                                    name = trimmedName,
                                    baseUrl = finalUrl,
                                    authEnabled = authEnabled,
                                    username = if (authEnabled) username else "",
                                    isDefault = isDefault,
                                    allowInsecureHttp = allowInsecureHttp,
                                    lastStatus = ServerStatus.UNKNOWN,
                                ),
                            )
                            if (authEnabled && password.isNotEmpty()) {
                                repository.setPassword(created.id, password)
                            }
                        } else {
                            repository.updateProfile(profileId) {
                                it.copy(
                                    name = trimmedName,
                                    baseUrl = finalUrl,
                                    authEnabled = authEnabled,
                                    username = if (authEnabled) username else "",
                                    isDefault = isDefault,
                                    allowInsecureHttp = allowInsecureHttp,
                                )
                            }
                            if (authEnabled && password.isNotEmpty()) {
                                repository.setPassword(profileId, password)
                            } else if (!authEnabled) {
                                repository.deletePassword(profileId)
                            }
                        }
                        serverViewModel.load()
                        onDone()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save")
            }
        }
    }
}

@Composable
private fun TextButtonLike(text: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) { Text(text) }
}
