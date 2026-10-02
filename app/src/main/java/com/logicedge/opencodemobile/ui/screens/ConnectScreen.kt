package com.logicedge.opencodemobile.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.unit.dp
import com.logicedge.opencodemobile.data.ConnectionState
import com.logicedge.opencodemobile.data.ServerRepository
import com.logicedge.opencodemobile.data.SessionInfo
import com.logicedge.opencodemobile.ui.ConnectionViewModel
import com.logicedge.opencodemobile.ui.ServerViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    serverId: String?,
    serverViewModel: ServerViewModel,
    connectionViewModel: ConnectionViewModel,
    repository: ServerRepository,
    onChat: (String, String) -> Unit,
    onBack: () -> Unit,
) {
    val profiles by serverViewModel.profiles.collectAsState()
    val state by connectionViewModel.state.collectAsState()
    val activeServer by connectionViewModel.activeServer.collectAsState()
    val lastError by connectionViewModel.lastError.collectAsState()
    val profile = profiles.firstOrNull { it.id == serverId }

    var sessions by remember { mutableStateOf<List<SessionInfo>>(emptyList()) }
    var sessionsLoading by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun loadSessions() {
        val current = profile ?: return
        scope.launch {
            sessionsLoading = true
            sessions = runCatching {
                repository.apiFor(current).listSessions()
            }.getOrDefault(emptyList())
            sessionsLoading = false
            refreshing = false
        }
    }

    LaunchedEffect(serverId) {
        if (serverId == null) {
            onBack()
            return@LaunchedEffect
        }
        val found = serverViewModel.profiles.value.firstOrNull { it.id == serverId }
        if (found != null) connectionViewModel.connect(found)
    }

    LaunchedEffect(state) {
        if (state == ConnectionState.CONNECTED && profile != null) {
            loadSessions()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(profile?.name ?: "Connect") },
                navigationIcon = {
                    IconButton(onClick = {
                        connectionViewModel.disconnect()
                        onBack()
                    }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        profile?.let { connectionViewModel.connect(it) }
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reconnect")
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                loadSessions()
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state) {
                ConnectionState.CHECKING -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    Text("Checking server…", modifier = Modifier.align(Alignment.CenterHorizontally))
                }
                ConnectionState.RECONNECTING -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    Text("Reconnecting…", modifier = Modifier.align(Alignment.CenterHorizontally))
                    OutlinedButton(
                        onClick = { connectionViewModel.cancelReconnect() },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) { Text("Cancel") }
                }
                ConnectionState.CONNECTED -> {
                    if (sessionsLoading && sessions.isEmpty()) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    } else if (sessions.isEmpty()) {
                        Text("No sessions yet. Start a new chat.")
                        NewChatButton(serverId = serverId, onChat = onChat)
                    } else {
                        NewChatButton(serverId = serverId, onChat = onChat)
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(sessions, key = { it.id }) { session ->
                                SessionCard(session = session, onClick = {
                                    onChat(serverId!!, session.id)
                                })
                            }
                        }
                    }
                }
                ConnectionState.AUTH_REQUIRED -> {
                    Text("Authentication required. Edit this server and set the password.")
                    lastError?.let { Text(it) }
                }
                ConnectionState.WRONG_CREDENTIALS -> {
                    Text("Wrong credentials. Check the username and password.")
                    lastError?.let { Text(it) }
                }
                else -> {
                    Text("Server is not reachable.")
                    lastError?.let { Text(it) }
                    Button(onClick = {
                        profile?.let { connectionViewModel.connect(it) }
                    }) { Text("Retry") }
                }
            }
            activeServer?.let {
                Text(
                    "Connected: ${it.baseUrl}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        }
    }
}

@Composable
private fun NewChatButton(serverId: String?, onChat: (String, String) -> Unit) {
    // Placeholder session id "new": ChatScreen creates the session on first send.
    Button(onClick = { onChat(serverId!!, "new") }, modifier = Modifier.fillMaxWidth()) {
        Text("New chat")
    }
}

@Composable
private fun SessionCard(session: SessionInfo, onClick: () -> Unit) {
    Card(modifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                session.title ?: "Untitled session",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            val date = remember(session.time.updated) {
                SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
                    .format(Date(session.time.updated))
            }
            Text(date, style = MaterialTheme.typography.bodySmall)
        }
    }
}
