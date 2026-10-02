package com.logicedge.opencodemobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.logicedge.opencodemobile.data.ConnectionState
import com.logicedge.opencodemobile.data.Health
import com.logicedge.opencodemobile.data.ServerProfile
import com.logicedge.opencodemobile.data.ServerRepository
import com.logicedge.opencodemobile.data.ServerStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ConnectionViewModel(private val repository: ServerRepository) : ViewModel() {

    var healthTimeoutMs: Long = Health.DEFAULT_TIMEOUT_MS
    var healthPollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS
    var exponentialReconnectEnabled: Boolean = true

    private val _state = MutableStateFlow(ConnectionState.IDLE)
    val state: StateFlow<ConnectionState> = _state

    private val _activeServer = MutableStateFlow<ServerProfile?>(null)
    val activeServer: StateFlow<ServerProfile?> = _activeServer

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private val _reconnectAttempt = MutableStateFlow(0)
    val reconnectAttempt: StateFlow<Int> = _reconnectAttempt

    private var monitorJob: Job? = null

    private suspend fun currentProfile(): ServerProfile? {
        val id = _activeServer.value?.id ?: return null
        return repository.load().firstOrNull { it.id == id }
    }

    fun connect(profile: ServerProfile) {
        cleanup()
        viewModelScope.launch {
            _activeServer.value = profile
            _state.value = ConnectionState.CHECKING
            _lastError.value = null
            val result = repository.checkHealth(profile, healthTimeoutMs)
            when (result.status) {
                ServerStatus.CONNECTED -> {
                    repository.markConnected(profile)
                    _activeServer.value = profile.copy(
                        lastStatus = ServerStatus.CONNECTED,
                    )
                    _state.value = ConnectionState.CONNECTED
                    _reconnectAttempt.value = 0
                    startMonitor()
                }
                ServerStatus.AUTH_REQUIRED -> {
                    _state.value = ConnectionState.AUTH_REQUIRED
                }
                ServerStatus.WRONG_CREDENTIALS -> {
                    _state.value = ConnectionState.WRONG_CREDENTIALS
                    _lastError.value = "Username or password is incorrect"
                }
                else -> {
                    _state.value = ConnectionState.UNREACHABLE
                    _lastError.value = "Server is not reachable"
                }
            }
        }
    }

    /**
     * One monitor loop owns polling and reconnecting — the old design ran a
     * poll job and a reconnect job concurrently on a stale profile snapshot.
     * Each iteration re-reads the profile so URL/password edits apply live.
     */
    private fun startMonitor() {
        monitorJob?.cancel()
        monitorJob = viewModelScope.launch {
            while (true) {
                if (_state.value == ConnectionState.CONNECTED) {
                    delay(healthPollIntervalMs)
                    val profile = currentProfile() ?: break
                    val result = repository.checkHealth(profile, healthTimeoutMs)
                    if (result.status != ServerStatus.CONNECTED) {
                        _state.value = ConnectionState.DISCONNECTED
                    }
                } else if (_state.value == ConnectionState.DISCONNECTED ||
                    _state.value == ConnectionState.RECONNECTING
                ) {
                    if (!exponentialReconnectEnabled) break
                    _state.value = ConnectionState.RECONNECTING
                    val attempt = _reconnectAttempt.value
                    delay(RECONNECT_DELAYS[minOf(attempt, RECONNECT_DELAYS.lastIndex)])
                    _reconnectAttempt.value = attempt + 1
                    val profile = currentProfile() ?: break
                    val result = repository.checkHealth(profile, healthTimeoutMs)
                    if (result.status == ServerStatus.CONNECTED) {
                        repository.markConnected(profile)
                        _activeServer.value = profile.copy(lastStatus = ServerStatus.CONNECTED)
                        _state.value = ConnectionState.CONNECTED
                        _lastError.value = null
                        _reconnectAttempt.value = 0
                    }
                } else {
                    break
                }
            }
        }
    }

    fun cancelReconnect() {
        monitorJob?.cancel()
        monitorJob = null
        _reconnectAttempt.value = 0
        _state.value = ConnectionState.DISCONNECTED
    }

    fun disconnect() {
        cleanup()
        _state.value = ConnectionState.DISCONNECTED
        _lastError.value = null
        _activeServer.value = null
    }

    private fun cleanup() {
        monitorJob?.cancel()
        monitorJob = null
        _reconnectAttempt.value = 0
    }

    companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 10_000L
        val RECONNECT_DELAYS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000, 30_000)
    }
}
