package com.logicedge.opencodemobile.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.logicedge.opencodemobile.data.AssistantMessage
import com.logicedge.opencodemobile.data.ChatMessage
import com.logicedge.opencodemobile.data.ChatPrefs
import com.logicedge.opencodemobile.data.ConnectionState
import com.logicedge.opencodemobile.data.PermissionDecision
import com.logicedge.opencodemobile.data.ServerRepository
import com.logicedge.opencodemobile.data.SystemMessage
import com.logicedge.opencodemobile.data.UserMessage
import com.logicedge.opencodemobile.notify.NotificationPrefs
import com.logicedge.opencodemobile.notify.SessionNotifier
import com.logicedge.opencodemobile.ui.ChatViewModel
import com.logicedge.opencodemobile.ui.ConnectionViewModel
import com.logicedge.opencodemobile.ui.MarkdownText
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    serverId: String?,
    sessionId: String?,
    repository: ServerRepository,
    chatViewModel: ChatViewModel,
    connectionViewModel: ConnectionViewModel,
    onBack: () -> Unit,
    onOpenServers: () -> Unit,
    onEditServer: (String) -> Unit,
    onDisconnected: () -> Unit,
) {
    val context = LocalContext.current
    val messages by chatViewModel.messages.collectAsState()
    val sessions by chatViewModel.sessions.collectAsState()
    val commands by chatViewModel.commands.collectAsState()
    val models by chatViewModel.models.collectAsState()
    val agents by chatViewModel.agents.collectAsState()
    val sending by chatViewModel.sending.collectAsState()
    val error by chatViewModel.error.collectAsState()
    val demoActive by chatViewModel.demoActive.collectAsState()
    val pendingPermission by chatViewModel.pendingPermission.collectAsState()
    val notificationAsk by chatViewModel.notificationAsk.collectAsState()
    val connState by connectionViewModel.state.collectAsState()
    val chatPrefs = remember { ChatPrefs(context) }
    val showThinking by chatPrefs.showThinking.collectAsState(initial = false)
    var draft by rememberSaveable { mutableStateOf("") }
    var activeSessionId by remember(sessionId) { mutableStateOf(if (sessionId == "new") null else sessionId) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showAgentPicker by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var osGranted by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current

    fun copyMessage(message: ChatMessage) {
        val text = when (message) {
            is UserMessage -> message.text
            is AssistantMessage -> message.textParts.joinToString("\n\n")
            is SystemMessage -> message.text
        }
        if (text.isNotBlank()) {
            clipboard.setText(AnnotatedString(text))
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        osGranted = granted
        chatViewModel.resolveNotificationAsk(accepted = true, osGranted = granted)
    }

    LaunchedEffect(serverId) {
        if (serverId == null) {
            onBack()
            return@LaunchedEffect
        }
        chatViewModel.notificationPrefs = NotificationPrefs(context)
        chatViewModel.notifier = SessionNotifier(context, chatViewModel.notificationPrefs!!)
        osGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        chatViewModel.attach(serverId, activeSessionId, repository.load())
    }

    LaunchedEffect(serverId) {
        if (serverId == null) {
            onBack()
            return@LaunchedEffect
        }
        chatViewModel.notificationPrefs = NotificationPrefs(context)
        chatViewModel.notifier = SessionNotifier(context, chatViewModel.notificationPrefs!!)
        osGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        chatViewModel.attach(serverId, activeSessionId, repository.load())
    }

    // Scroll the transcript when the tail changes — a walking reply streams
    // text into the same-message message id, so size alone misses the updates.
    val contentKey = messages.lastOrNull()?.let { m ->
        val tail = when (m) {
            is UserMessage -> m.text.length
            is AssistantMessage ->
                m.textParts.sumOf { it.length } + m.thinkingParts.sumOf { it.length }
            is SystemMessage -> m.text.length
        }
        "${m.id}:${messages.size}:$tail"
    }
    LaunchedEffect(contentKey) {
        if (messages.isNotEmpty()) {
            scope.launch { listState.animateScrollToItem(messages.size - 1) }
        }
    }

    val sessionTitle = sessions.firstOrNull { it.id == activeSessionId }?.title
    val currentAgent = sessions.firstOrNull { it.id == activeSessionId }?.agent

    if (showModelPicker && activeSessionId != null) {
        val target = activeSessionId!!
        AlertDialog(
            onDismissRequest = { showModelPicker = false },
            title = { Text("Model") },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    models.forEach { model ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showModelPicker = false
                                    chatViewModel.switchModel(target, model)
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(model.displayName, modifier = Modifier.weight(1f))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showModelPicker = false }) { Text("Close") }
            },
        )
    }

    if (showAgentPicker && activeSessionId != null) {
        val target = activeSessionId!!
        AlertDialog(
            onDismissRequest = { showAgentPicker = false },
            title = { Text("Agent") },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    agents.forEach { agent ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showAgentPicker = false
                                    chatViewModel.switchAgent(target, agent)
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(agent.name)
                                agent.description?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                        maxLines = 2,
                                    )
                                }
                            }
                            if (agent.name == currentAgent) {
                                Text(
                                    "✓",
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAgentPicker = false }) { Text("Close") }
            },
        )
    }

    if (showRename && activeSessionId != null) {
        val target = activeSessionId!!
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename chat") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRename = false
                        if (renameText.isNotBlank()) {
                            chatViewModel.renameSession(target, renameText.trim())
                        }
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) { Text("Cancel") }
            },
        )
    }

    if (showDeleteConfirm && activeSessionId != null) {
        val target = activeSessionId!!
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete chat?") },
            text = { Text("This removes the session and its history.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    chatViewModel.deleteSession(target) { onBack() }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }
    val screenTitle = when {
        demoActive -> "Demo chat"
        sessionTitle != null -> sessionTitle
        activeSessionId == null -> "New chat"
        else -> "Session"
    }

    pendingPermission?.let { request ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Permission needed") },
            text = {
                Text(
                    "${request.action}\n${request.resources.joinToString("\n")}\n" +
                        (request.message ?: ""),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    activeSessionId?.let {
                        chatViewModel.replyPermission(it, request.id, PermissionDecision.ONCE)
                    }
                }) { Text("Allow once") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        activeSessionId?.let {
                            chatViewModel.replyPermission(it, request.id, PermissionDecision.REJECT)
                        }
                    }) { Text("Deny") }
                    TextButton(onClick = {
                        activeSessionId?.let {
                            chatViewModel.replyPermission(it, request.id, PermissionDecision.ALWAYS)
                        }
                    }) { Text("Always") }
                }
            },
        )
    }

    notificationAsk?.let { ask ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Session notifications?") },
            text = { Text("${ask.title}\n${ask.body.take(140)}") },
            confirmButton = {
                TextButton(onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !osGranted) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        chatViewModel.resolveNotificationAsk(accepted = true, osGranted = true)
                    }
                }) { Text("Yes") }
            },
            dismissButton = {
                TextButton(onClick = {
                    chatViewModel.resolveNotificationAsk(accepted = false, osGranted = false)
                }) { Text("No") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(screenTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (sending && activeSessionId != null) {
                        IconButton(onClick = { chatViewModel.stop(activeSessionId!!) }) {
                            Icon(Icons.Filled.Stop, contentDescription = "Stop")
                        }
                    }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Session menu")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Status: $connState") },
                            onClick = { menuExpanded = false },
                        )
                        DropdownMenuItem(
                            text = { Text("Refresh messages") },
                            onClick = {
                                menuExpanded = false
                                activeSessionId?.let { chatViewModel.refreshMessages(it) }
                            },
                        )
                        if (connState == ConnectionState.RECONNECTING) {
                            DropdownMenuItem(
                                text = { Text("Cancel reconnect") },
                                onClick = {
                                    menuExpanded = false
                                    connectionViewModel.cancelReconnect()
                                },
                            )
                        } else if (connState != ConnectionState.CONNECTED) {
                            DropdownMenuItem(
                                text = { Text("Reconnect") },
                                onClick = {
                                    menuExpanded = false
                                    chatViewModel.profile.value?.let { connectionViewModel.connect(it) }
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(if (showThinking) "Hide model thinking" else "Show model thinking") },
                            onClick = {
                                menuExpanded = false
                                scope.launch { chatPrefs.setShowThinking(!showThinking) }
                            },
                        )
                        if (models.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text("Model") },
                                onClick = {
                                    menuExpanded = false
                                    showModelPicker = true
                                },
                            )
                        }
                        if (agents.isNotEmpty()) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (currentAgent != null) "Agent ($currentAgent)"
                                        else "Agent",
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    showAgentPicker = true
                                },
                            )
                        }
                        if (activeSessionId != null) {
                            DropdownMenuItem(
                                text = { Text("Rename chat") },
                                onClick = {
                                    menuExpanded = false
                                    renameText = sessionTitle.orEmpty()
                                    showRename = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete chat") },
                                onClick = {
                                    menuExpanded = false
                                    showDeleteConfirm = true
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Switch server") },
                            onClick = {
                                menuExpanded = false
                                connectionViewModel.disconnect()
                                onOpenServers()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Edit server") },
                            onClick = {
                                menuExpanded = false
                                serverId?.let { onEditServer(it) }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Disconnect") },
                            onClick = {
                                menuExpanded = false
                                connectionViewModel.disconnect()
                                onDisconnected()
                            },
                        )
                    }
                },
            )
        },
        bottomBar = {
            val commandToken = draft.takeIf { it.startsWith("/") }
                ?.substringBefore(" ")?.drop(1)
            val commandArgs = draft.substringAfter(" ", missingDelimiterValue = "")
                .takeIf { draft.contains(" ") }.orEmpty()
            val suggestions = if (commandToken != null && commands.isNotEmpty()) {
                commands.filter { it.name.startsWith(commandToken, ignoreCase = true) }
            } else {
                emptyList()
            }
            Column(modifier = Modifier.fillMaxWidth()) {
                if (suggestions.isNotEmpty()) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                    ) {
                        Column(
                            Modifier
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(vertical = 4.dp),
                        ) {
                            suggestions.forEach { cmd ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            draft = "/${cmd.name}" +
                                                (if (commandArgs.isNotBlank()) " $commandArgs" else " ")
                                        }
                                        .padding(horizontal = 14.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "/${cmd.name}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.widthIn(min = 96.dp),
                                    )
                                    cmd.description?.let {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = { Text("Message…  ( / for commands )") },
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.weight(1f),
                        maxLines = 5,
                    )
                    FilledIconButton(
                        onClick = {
                            val text = draft
                            draft = ""
                            val slashName = text.substringBefore(" ").drop(1)
                                .takeIf { text.startsWith("/") && it.isNotBlank() }
                                ?.takeIf { name ->
                                    commands.any { it.name.equals(name, ignoreCase = true) }
                                }
                            if (slashName != null) {
                                val args = text.substringAfter(" ", missingDelimiterValue = "")
                                chatViewModel.runCommand(activeSessionId, slashName, args) { created ->
                                    activeSessionId = created
                                }
                            } else {
                                chatViewModel.send(activeSessionId, text) { created ->
                                    activeSessionId = created
                                }
                            }
                        },
                        enabled = draft.isNotBlank() && !sending,
                    ) {
                        Icon(Icons.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (messages.isEmpty() && !sending) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No messages yet.\nSay hello to your agent.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(messages, key = { _, it -> it.id }) { index, message ->
                        MessageBubble(
                            message = message,
                            showThinking = showThinking,
                            onCopy = { copyMessage(message) },
                            modifier = Modifier.animateItem(),
                            live = sending && index == messages.lastIndex,
                        )
                    }
                    if (sending) {
                        item {
                            Row(
                                modifier = Modifier
                                    .animateItem()
                                    .padding(horizontal = 4.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                PulsingDots()
                                Text(
                                    "Working…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private val userBubbleShape = RoundedCornerShape(
    topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp,
)
private val assistantBubbleShape = RoundedCornerShape(
    topStart = 4.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp,
)

@Composable
private fun timeLabel(created: Long): String {
    val formatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    return if (created > 0) formatter.format(Date(created)) else ""
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: ChatMessage,
    showThinking: Boolean,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
    live: Boolean = false,
) {
    when (message) {
        is UserMessage -> {
            Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                Card(
                    shape = userBubbleShape,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                    modifier = Modifier
                        .widthIn(max = 304.dp)
                        .combinedClickable(onClick = {}, onLongClick = onCopy),
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(message.text, style = MaterialTheme.typography.bodyLarge)
                        Row {
                            Spacer(Modifier.weight(1f))
                            Text(
                                timeLabel(message.created),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                            )
                        }
                    }
                }
            }
        }
        is AssistantMessage -> {
            Card(
                shape = assistantBubbleShape,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = modifier
                    .fillMaxWidth(0.92f)
                    .combinedClickable(onClick = {}, onLongClick = onCopy),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    val header = listOfNotNull(message.agent, message.model).joinToString(" · ")
                    if (header.isNotEmpty()) {
                        Text(
                            header,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    if (showThinking && message.thinkingParts.isNotEmpty()) {
                        ThinkingBlock(texts = message.thinkingParts, working = live)
                        Spacer(Modifier.height(8.dp))
                    }
                    message.textParts.forEach {
                        MarkdownText(it)
                    }
                    if (message.toolCalls.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            message.toolCalls.forEach { tool ->
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(
                                            horizontal = 10.dp,
                                            vertical = 3.dp,
                                        ),
                                    ) {
                                        Icon(
                                            Icons.Filled.Build,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.size(12.dp),
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            tool,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    message.error?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Row {
                        Spacer(Modifier.weight(1f))
                        Text(
                            timeLabel(message.created),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                        )
                    }
                }
            }
        }
        is SystemMessage -> {
            Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Text(
                    message.text,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun PulsingDots() {
    val transition = rememberInfiniteTransition(label = "working")
    val base = MaterialTheme.colorScheme.outline
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { i ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 500, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(i * 160),
                ),
                label = "dot$i",
            )
            Canvas(Modifier.size(6.dp)) {
                drawCircle(color = base, alpha = alpha)
            }
        }
    }
}

@Composable
private fun ThinkingBlock(texts: List<String>, working: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    // Live reasoning: when this is the message currently being produced,
    // open it as it streams instead of making the user expand it after.
    val effectiveExpanded = expanded || working
    val arrowRotation by animateFloatAsState(
        if (effectiveExpanded) 180f else 0f,
        animationSpec = tween(300),
        label = "thinkArrow",
    )
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (working) "Thinking…" else "Thinking",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (effectiveExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier
                        .size(18.dp)
                        .graphicsLayer { rotationZ = arrowRotation },
                )
            }
            AnimatedVisibility(
                visible = effectiveExpanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column {
                    Spacer(Modifier.height(6.dp))
                    texts.forEach {
                        MarkdownText(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
