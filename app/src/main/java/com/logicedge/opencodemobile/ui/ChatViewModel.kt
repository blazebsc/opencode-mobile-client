package com.logicedge.opencodemobile.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.logicedge.opencodemobile.data.ApiVersion
import com.logicedge.opencodemobile.data.AssistantMessage
import com.logicedge.opencodemobile.data.ChatMessage
import com.logicedge.opencodemobile.data.DemoMode
import com.logicedge.opencodemobile.data.DemoSession
import com.logicedge.opencodemobile.data.OpenCodeApi
import com.logicedge.opencodemobile.data.OpenCodeJson
import com.logicedge.opencodemobile.data.PermissionDecision
import com.logicedge.opencodemobile.data.PermissionRequest
import com.logicedge.opencodemobile.data.ServerProfile
import com.logicedge.opencodemobile.data.ServerRepository
import com.logicedge.opencodemobile.data.SessionInfo
import com.logicedge.opencodemobile.data.ChatAgent
import com.logicedge.opencodemobile.data.ChatModel
import com.logicedge.opencodemobile.data.Health
import com.logicedge.opencodemobile.data.SlashCommand
import com.logicedge.opencodemobile.data.SystemMessage
import com.logicedge.opencodemobile.data.UserMessage
import com.logicedge.opencodemobile.notify.NotificationPrefs
import com.logicedge.opencodemobile.notify.NotifyDecision
import com.logicedge.opencodemobile.notify.NotifyPrefs
import com.logicedge.opencodemobile.notify.SessionNotifier
import com.logicedge.opencodemobile.notify.decideNotify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener

data class NotificationAsk(val title: String, val body: String)

class ChatViewModel(private val repository: ServerRepository) : ViewModel() {

    var notifier: SessionNotifier? = null
    var notificationPrefs: NotificationPrefs? = null

    private val _profile = MutableStateFlow<ServerProfile?>(null)
    val profile: StateFlow<ServerProfile?> = _profile

    private val _sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    val sessions: StateFlow<List<SessionInfo>> = _sessions

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _demoActive = MutableStateFlow(false)
    val demoActive: StateFlow<Boolean> = _demoActive

    private val _pendingPermission = MutableStateFlow<PermissionRequest?>(null)
    val pendingPermission: StateFlow<PermissionRequest?> = _pendingPermission

    private val _commands = MutableStateFlow<List<SlashCommand>>(emptyList())
    val commands: StateFlow<List<SlashCommand>> = _commands

    private val _models = MutableStateFlow<List<ChatModel>>(emptyList())
    val models: StateFlow<List<ChatModel>> = _models

    private val _agents = MutableStateFlow<List<ChatAgent>>(emptyList())
    val agents: StateFlow<List<ChatAgent>> = _agents

    private val _notificationAsk = MutableStateFlow<NotificationAsk?>(null)
    val notificationAsk: StateFlow<NotificationAsk?> = _notificationAsk

    private var api: OpenCodeApi? = null
    private var waitJob: Job? = null
    private var sendJob: Job? = null
    private var sseRefreshJob: Job? = null
    private var eventSource: EventSource? = null
    private var currentSessionId: String? = null
    private var sseAttempts: Int = 0

    fun attach(serverId: String, sessionId: String?, allProfiles: List<ServerProfile>) {
        eventSource?.cancel()
        eventSource = null
        waitJob?.cancel()
        sendJob?.cancel()
        sseRefreshJob?.cancel()
        sseAttempts = 0
        _demoActive.value = false
        _messages.value = emptyList()
        _sessions.value = emptyList()
        _commands.value = emptyList()
        _models.value = emptyList()
        _agents.value = emptyList()
        _error.value = null
        _pendingPermission.value = null
        _sending.value = false
        currentSessionId = sessionId
        api = null
        viewModelScope.launch {
            val found = allProfiles.firstOrNull { it.id == serverId }
            if (found == null) {
                _error.value = "Server profile not found"
                return@launch
            }
            var profile = found
            if (profile.apiVersion == ApiVersion.UNKNOWN) {
                // Never chat on an undetected version: UNKNOWN resolves to v2
                // paths, which 404 against v1 servers.
                repository.checkHealth(profile, Health.DEFAULT_TIMEOUT_MS)
                profile = repository.load().firstOrNull { it.id == serverId } ?: found
            }
            _profile.value = profile
            val password = repository.getPassword(profile.id)
            if (DemoMode.isDemoCredentials(profile.username, password)) {
                _demoActive.value = true
                _messages.value = DemoSession.seedMessages(profile.name)
                return@launch
            }
            val client = repository.apiFor(profile)
            api = client
            runCatching { _sessions.value = client.listSessions() }
            runCatching { _commands.value = client.listCommands() }
            runCatching { _models.value = client.listModels() }
            runCatching { _agents.value = client.listAgents() }
            if (sessionId != null) {
                fetchMessages(sessionId)
                subscribeSessionEvents(client, sessionId)
                pollPermissions(sessionId)
            }
        }
    }

    fun newSession(onCreated: (String) -> Unit) {
        val client = api ?: return
        viewModelScope.launch {
            runCatching { client.createSession() }
                .onSuccess { session ->
                    _sessions.value = runCatching { client.listSessions() }.getOrDefault(_sessions.value)
                    currentSessionId = session.id
                    subscribeSessionEvents(client, session.id)
                    onCreated(session.id)
                }
                .onFailure { _error.value = "Could not create session: ${it.message}" }
        }
    }

    fun refreshMessages(sessionId: String) {
        viewModelScope.launch { fetchMessages(sessionId) }
    }

    private suspend fun fetchMessages(sessionId: String): List<ChatMessage> {
        val client = api ?: return _messages.value
        return runCatching { client.getMessages(sessionId) }
            .onSuccess { _messages.value = it.sortedBy { msg -> msg.created } }
            .onFailure {
                Log.e("ChatViewModel", "fetchMessages failed for $sessionId", it)
                _error.value = "Could not load messages (${it::class.simpleName}): " +
                    (it.message ?: "(no message)")
            }
            .getOrDefault(_messages.value)
    }

    fun send(sessionId: String?, text: String, onSession: (String) -> Unit = {}) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _sending.value) return
        if (_demoActive.value) {
            val current = _messages.value.toMutableList()
            current += UserMessage("demo-u-${current.size}", current.size.toLong(), trimmed)
            current += AssistantMessage(
                "demo-a-${current.size}", current.size.toLong(),
                textParts = listOf(DemoMode.replyTo(trimmed)),
            )
            _messages.value = current
            return
        }
        val client = api ?: return
        sendJob?.cancel()
        sendJob = viewModelScope.launch {
            _sending.value = true
            _error.value = null
            try {
                val target = sessionId ?: client.createSession().also {
                    _sessions.value = runCatching { client.listSessions() }.getOrDefault(_sessions.value)
                    onSession(it.id)
                }.id
                currentSessionId = target
                subscribeSessionEvents(client, target)
                client.sendPrompt(target, trimmed)
                settleAfterPrompt(client, target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "Send failed: ${e.message}"
                _sending.value = false
                offerNotify("OpenCode error", e.message ?: "Send failed")
            }
        }
    }

    fun runCommand(sessionId: String?, name: String, args: String, onSession: (String) -> Unit = {}) {
        if (_sending.value) return
        if (_demoActive.value) {
            send(null, "/$name $args".trim(), onSession)
            return
        }
        val client = api ?: return
        sendJob?.cancel()
        sendJob = viewModelScope.launch {
            _sending.value = true
            _error.value = null
            try {
                val target = sessionId ?: client.createSession().also {
                    _sessions.value = runCatching { client.listSessions() }.getOrDefault(_sessions.value)
                    onSession(it.id)
                }.id
                currentSessionId = target
                subscribeSessionEvents(client, target)
                client.runCommand(target, name, args)
                settleAfterPrompt(client, target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "Command failed: ${e.message}"
            } finally {
                _sending.value = false
            }
        }
    }

    private suspend fun settleAfterPrompt(client: OpenCodeApi, target: String) {
        fetchMessages(target)
        pollPermissions(target)
        waitJob?.cancel()
        waitJob = viewModelScope.launch {
            try {
                withTimeoutOrNull(WAIT_TIMEOUT_MS) { client.waitForSession(target) }
                settleByFinish(client, target)
                notifyCompletion()
            } finally {
                _sending.value = false
            }
        }
    }

    /**
     * Settles on terminal message state (finish/error/idle), not list size:
     * streaming edits an existing message without changing the count, and the
     * old size-equality check exited early on slow replies while pointlessly
     * re-polling fast ones to the 60s cap.
     */
    private suspend fun settleByFinish(client: OpenCodeApi, sessionId: String) {
        repeat(POLL_ROUNDS) {
            delay(POLL_INTERVAL_MS)
            val fresh = try {
                client.getMessages(sessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return
            }
            _messages.value = fresh.sortedBy { it.created }
            pollPermissions(sessionId)
            when (val last = fresh.maxByOrNull { it.created }) {
                is AssistantMessage -> if (last.finish != null || last.error != null) return
                is SystemMessage -> return
                else -> Unit
            }
        }
    }

    fun pollPermissions(sessionId: String) {
        val client = api ?: return
        viewModelScope.launch {
            // A failed poll must not dismiss a visible prompt: only a
            // successful empty list clears it.
            val pending = try {
                client.listPermissions(sessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch
            }
            val first = pending.firstOrNull()
            if (first == null) {
                _pendingPermission.value = null
                return@launch
            }
            if (_pendingPermission.value?.id != first.id) {
                _pendingPermission.value = first
                offerNotify(
                    "Permission needed",
                    "${first.action}: ${first.resources.firstOrNull() ?: first.message ?: ""}",
                )
            }
        }
    }

    fun replyPermission(sessionId: String, requestId: String, decision: PermissionDecision) {
        val client = api ?: return
        viewModelScope.launch {
            runCatching { client.replyPermission(sessionId, requestId, decision) }
                .onSuccess {
                    _pendingPermission.value = null
                    notifier?.cancel(sessionId)
                    refreshMessages(sessionId)
                }
                .onFailure { _error.value = "Permission reply failed: ${it.message}" }
        }
    }

    fun switchModel(sessionId: String, model: ChatModel) {
        val client = api ?: return
        viewModelScope.launch {
            runCatching { client.switchModel(sessionId, model.providerID, model.id) }
                .onSuccess { refreshMessages(sessionId) }
                .onFailure { _error.value = "Model switch failed: ${it.message}" }
        }
    }

    fun switchAgent(sessionId: String, agent: ChatAgent) {
        val client = api ?: return
        viewModelScope.launch {
            runCatching { client.switchAgent(sessionId, agent.name) }
                .onSuccess { refreshMessages(sessionId) }
                .onFailure { _error.value = "Agent switch failed: ${it.message}" }
        }
    }

    fun deleteSession(sessionId: String, onDeleted: () -> Unit = {}) {
        val client = api ?: return
        viewModelScope.launch {
            runCatching { client.deleteSession(sessionId) }
                .onSuccess {
                    _sessions.value = runCatching { client.listSessions() }.getOrDefault(_sessions.value)
                    onDeleted()
                }
                .onFailure { _error.value = "Delete failed: ${it.message}" }
        }
    }

    fun renameSession(sessionId: String, title: String) {
        val client = api ?: return
        viewModelScope.launch {
            runCatching { client.renameSession(sessionId, title) }
                .onSuccess {
                    _sessions.value = runCatching { client.listSessions() }.getOrDefault(_sessions.value)
                }
                .onFailure { _error.value = "Rename failed: ${it.message}" }
        }
    }

    private fun subscribeSessionEvents(client: OpenCodeApi, sessionId: String) {
        eventSource?.cancel()
        eventSource = client.subscribeEvents(object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                sseAttempts = 0
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                // Resubscribe with backoff; the guard stops the loop once the
                // user moves to another session or leaves the chat.
                viewModelScope.launch {
                    val attempt = sseAttempts++
                    delay(SSE_BACKOFF_MS shl minOf(attempt, 4))
                    if (currentSessionId == sessionId) {
                        subscribeSessionEvents(client, sessionId)
                    }
                }
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String,
            ) {
                val session = extractEventSession(data) ?: return
                if (session != sessionId) return
                // Debounce: token-streaming bursts would otherwise fire a full
                // history fetch per event.
                sseRefreshJob?.cancel()
                sseRefreshJob = viewModelScope.launch {
                    delay(SSE_DEBOUNCE_MS)
                    fetchMessages(sessionId)
                    pollPermissions(sessionId)
                }
            }
        })
    }

    private fun notifyCompletion() {
        val latest = _messages.value.lastOrNull() as? AssistantMessage ?: return
        val preview = latest.textParts.firstOrNull()
            ?: latest.error
            ?: if (latest.toolCalls.isNotEmpty()) "Used: ${latest.toolCalls.joinToString(", ")}" else return
        offerNotify("Agent replied", preview)
    }

    private fun offerNotify(title: String, body: String, tag: String? = currentSessionId) {
        val prefsSource = notificationPrefs ?: return
        viewModelScope.launch {
            when (decideNotify(prefsSource.load())) {
                NotifyDecision.EMIT -> notifier?.maybeNotify(title, body, tag)
                NotifyDecision.ASK -> _notificationAsk.value = NotificationAsk(title, body)
                NotifyDecision.IGNORE -> Unit
            }
        }
    }

    fun resolveNotificationAsk(accepted: Boolean, osGranted: Boolean) {
        val prefsSource = notificationPrefs ?: run {
            _notificationAsk.value = null
            return
        }
        viewModelScope.launch {
            if (accepted) {
                prefsSource.save(
                    NotifyPrefs(enabled = osGranted, settingsConfigured = true),
                )
                if (osGranted) {
                    _notificationAsk.value?.let { ask ->
                        notifier?.maybeNotify(ask.title, ask.body)
                    }
                }
            } else {
                prefsSource.save(
                    NotifyPrefs(enabled = false, customPromptAnswered = true),
                )
            }
            _notificationAsk.value = null
        }
    }

    fun stop(sessionId: String) {
        sendJob?.cancel()
        waitJob?.cancel()
        _sending.value = false
        val client = api ?: return
        viewModelScope.launch {
            // Best-effort: with cancellable I/O above, cancelling sendJob
            // already aborts the in-flight request server-side state aside.
            runCatching { client.interruptSession(sessionId) }
                .onFailure { _error.value = "Stop failed: ${it.message}" }
            fetchMessages(sessionId)
        }
    }

    override fun onCleared() {
        eventSource?.cancel()
        eventSource = null
        super.onCleared()
    }

    companion object {
        const val WAIT_TIMEOUT_MS = 120_000L
        const val POLL_INTERVAL_MS = 2_000L
        const val POLL_ROUNDS = 30
        const val SSE_DEBOUNCE_MS = 1_000L
        const val SSE_BACKOFF_MS = 2_000L

        fun extractEventSession(data: String): String? {
            return runCatching {
                val root = OpenCodeJson.parseToJsonElement(data).jsonObject
                root["sessionID"]?.jsonPrimitive?.content
                    ?: root["data"]?.jsonObject?.get("sessionID")?.jsonPrimitive?.content
            }.getOrNull()
        }
    }
}
