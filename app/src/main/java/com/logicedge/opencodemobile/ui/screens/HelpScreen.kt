package com.logicedge.opencodemobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Help") },
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
            Text("Start your OpenCode server", style = MaterialTheme.typography.titleMedium)
            Text("No authentication:")
            Text("opencode web --hostname 0.0.0.0 --port 4096")
            Text("With authentication (recommended):")
            Text("export OPENCODE_SERVER_PASSWORD=\"your-secret-password\"\nopencode serve --hostname 0.0.0.0 --port 4096")
            Spacer(Modifier.height(4.dp))
            Text("Troubleshooting", style = MaterialTheme.typography.titleMedium)
            Text("Cannot connect — verify the server is running, the URL is correct, the port is not firewalled, and the server listens on 0.0.0.0 (not 127.0.0.1).")
            Text("Auth required — the server returned 401 and no password is stored. Edit the server and set the password matching OPENCODE_SERVER_PASSWORD.")
            Text("Wrong credentials — try: curl -u opencode:your-password http://server:4096/api/session")
            Text("LAN HTTP is acceptable on trusted local networks only. For remote access use a VPN (Tailscale, WireGuard) or an HTTPS reverse proxy. Do not expose OpenCode directly to the public internet.")
        }
    }
}
