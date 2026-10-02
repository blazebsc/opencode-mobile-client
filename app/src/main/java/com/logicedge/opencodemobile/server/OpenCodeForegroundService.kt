package com.logicedge.opencodemobile.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class OpenCodeForegroundService : Service() {

    companion object {
        private const val TAG = "OpenCodeFGService"
        private const val NOTIFICATION_CHANNEL_ID = "opencode_server_channel"
        private const val NOTIFICATION_ID = 1337
        const val ACTION_START_SERVER = "com.logicedge.opencodemobile.START_SERVER"
        const val ACTION_STOP_SERVER = "com.logicedge.opencodemobile.STOP_SERVER"

        fun startIntent(context: Context): Intent =
            Intent(context, OpenCodeForegroundService::class.java).setAction(ACTION_START_SERVER)

        fun stopIntent(context: Context): Intent =
            Intent(context, OpenCodeForegroundService::class.java).setAction(ACTION_STOP_SERVER)
    }

    private val binder = LocalBinder()
    private lateinit var serverManager: OpenCodeServerManager
    private lateinit var bootstrapInstaller: BootstrapInstaller
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    var isServerRunning = false
        private set

    @Volatile
    var statusText: String = "Starting…"
        private set

    inner class LocalBinder : Binder() {
        fun getService(): OpenCodeForegroundService = this@OpenCodeForegroundService
    }

    override fun onCreate() {
        super.onCreate()
        serverManager = OpenCodeServerManager(this)
        bootstrapInstaller = BootstrapInstaller(this)
        createNotificationChannel()
        startForegroundService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVER -> {
                stopServerAndService()
                return START_NOT_STICKY
            }
            else -> startServerSequence()
        }
        return START_STICKY
    }

    private fun startServerSequence() {
        serviceScope.launch {
            try {
                if (!bootstrapInstaller.isBootstrapExtracted()) {
                    if (!bootstrapInstaller.hasBootstrapAsset()) {
                        updateNotification("Bootstrap asset missing — reinstall the app")
                        return@launch
                    }
                    val result = bootstrapInstaller.extractBootstrap { progress ->
                        updateNotification("Extracting bootstrap: $progress%")
                    }
                    if (result is BootstrapInstaller.Result.Error) {
                        updateNotification("Bootstrap extraction failed")
                        return@launch
                    }
                }
                updateNotification("Installing OpenCode CLI...")
                if (serverManager.installOpenCodeCLI() is OpenCodeServerManager.Result.Error) {
                    updateNotification("OpenCode CLI installation failed")
                    return@launch
                }
                updateNotification("Configuring OpenCode...")
                if (serverManager.writeOpenCodeConfig() is OpenCodeServerManager.Result.Error) {
                    updateNotification("Configuration failed")
                    return@launch
                }
                updateNotification("Starting OpenCode server...")
                if (serverManager.startServer() is OpenCodeServerManager.Result.Error) {
                    updateNotification("Failed to start server")
                    return@launch
                }
                isServerRunning = true
                updateNotification("OpenCode server running (127.0.0.1:4096)")
            } catch (e: Exception) {
                Log.e(TAG, "Server startup failed", e)
                updateNotification("Server startup failed: ${e.message}")
            }
        }
    }

    private fun stopServerAndService() {
        serviceScope.launch {
            try {
                if (isServerRunning) {
                    serverManager.stopServer()
                    isServerRunning = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping server", e)
            } finally {
                stopSelf()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "OpenCode Server",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "On-device OpenCode server status"
                enableLights(false)
                enableVibration(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        statusText = text
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("OpenCode")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopPendingIntent,
            )
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, createNotification(text))
    }

    private fun startForegroundService() {
        try {
            val notification = createNotification("OpenCode server starting...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        serviceScope.launch {
            try {
                if (isServerRunning) serverManager.stopServer()
            } catch (e: Exception) {
                Log.e(TAG, "Error during service destruction", e)
            }
        }
        serviceScope.cancel()
        super.onDestroy()
    }
}
