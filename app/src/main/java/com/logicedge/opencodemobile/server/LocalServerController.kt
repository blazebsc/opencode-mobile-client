package com.logicedge.opencodemobile.server

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

sealed interface LocalServerState {
    data object Idle : LocalServerState
    data class Working(val step: String) : LocalServerState
    data object Running : LocalServerState
    data class Failed(val message: String) : LocalServerState
}

class LocalServerController(context: Context) {

    private val appContext = context.applicationContext
    private val manager = OpenCodeServerManager(appContext)
    private val installer = BootstrapInstaller(appContext)

    private val _state = MutableStateFlow<LocalServerState>(LocalServerState.Idle)
    val state: StateFlow<LocalServerState> = _state

    suspend fun refresh(): LocalServerState = withContext(Dispatchers.IO) {
        val running = manager.isServerRunning()
        _state.value = if (running) LocalServerState.Running else LocalServerState.Idle
        _state.value
    }

    fun bootstrapAvailable(): Boolean = installer.hasBootstrapAsset()

    fun bootstrapExtracted(): Boolean = installer.isBootstrapExtracted()

    suspend fun setZenApiKey(key: String): Boolean = withContext(Dispatchers.IO) {
        manager.setZenApiKey(key) is OpenCodeServerManager.Result.Success
    }

    fun start() {
        _state.value = LocalServerState.Working("Starting on-device server…")
        val intent = OpenCodeForegroundService.startIntent(appContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent)
        } else {
            appContext.startService(intent)
        }
        _state.value = LocalServerState.Running
    }

    fun stop() {
        appContext.startService(OpenCodeForegroundService.stopIntent(appContext))
        _state.value = LocalServerState.Idle
    }
}
