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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.logicedge.opencodemobile.data.ServerProfile
import com.logicedge.opencodemobile.ui.ServerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    serverViewModel: ServerViewModel,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onBack: () -> Unit,
) {
    val profiles by serverViewModel.profiles.collectAsState()
    val loading by serverViewModel.loading.collectAsState()
    var pendingDelete by remember { mutableStateOf<ServerProfile?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        serverViewModel.load()
        serverViewModel.refreshStatuses()
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000L)
            serverViewModel.refreshStatuses()
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete server?") },
            text = { Text("Delete \"${target.name}\"? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    serverViewModel.delete(target.id)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Servers") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = "Add server")
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    serverViewModel.refreshStatuses { refreshing = false }
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
        if (loading && profiles.isEmpty()) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
            }
            return@PullToRefreshBox
        }
        if (profiles.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No servers yet")
                Spacer(Modifier.height(12.dp))
                Button(onClick = onAdd) { Text("Add your first server") }
            }
            return@PullToRefreshBox
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(serverViewModel.sortedProfiles, key = { it.id }) { profile ->
                ServerCard(
                    profile = profile,
                    onOpen = { onOpen(profile.id) },
                    onEdit = { onEdit(profile.id) },
                    onDuplicate = { serverViewModel.duplicate(profile.id) },
                    onSetDefault = { serverViewModel.setDefault(profile.id) },
                    onDelete = { pendingDelete = profile },
                )
            }
        }
        }
    }
}

@Composable
fun ServerCard(
    profile: ServerProfile,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onSetDefault: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(status = profile.lastStatus)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium)
                    Text(profile.baseUrl, style = MaterialTheme.typography.bodySmall)
                    profile.serverVersion?.let {
                        Text(
                            "${profile.apiVersion.serial} · $it",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Options")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(text = { Text("Edit") }, onClick = {
                        menuExpanded = false
                        onEdit()
                    })
                    DropdownMenuItem(text = { Text("Duplicate") }, onClick = {
                        menuExpanded = false
                        onDuplicate()
                    })
                    if (!profile.isDefault) {
                        DropdownMenuItem(text = { Text("Set Default") }, onClick = {
                            menuExpanded = false
                            onSetDefault()
                        })
                    }
                    DropdownMenuItem(text = { Text("Delete") }, onClick = {
                        menuExpanded = false
                        onDelete()
                    })
                }
            }
            if (profile.isDefault || profile.authEnabled) {
                Spacer(Modifier.height(4.dp))
                Text(
                    listOfNotNull(
                        "Default".takeIf { profile.isDefault },
                        "Auth".takeIf { profile.authEnabled },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                Text("Connect")
            }
        }
    }
}
