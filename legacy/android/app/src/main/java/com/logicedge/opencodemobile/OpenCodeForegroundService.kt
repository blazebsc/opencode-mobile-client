package com.logicedge.opencodemobile

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
import kotlinx.coroutines.launch

/**
 * OpenCodeForegroundService: Manages OpenCode server lifecycle as an Android foreground service.
 *
 * Responsibilities:
 * 1. Extracts bootstrap (if needed)
 * 2. Installs OpenCode CLI (if needed)
 * 3. Launches OpenCode server (opencode serve on 127.0.0.1:4096)
 * 4. Displays persistent notification while running
 * 5. Requests battery optimization exemption on first run
 * 6. Survives app backgrounding
 */
class OpenCodeForegroundService : Service() {

    companion object {
        private const val TAG = "OpenCodeFGService"
        private const val NOTIFICATION_CHANNEL_ID = "opencode_server_channel"
        private const val NOTIFICATION_ID = 1337
        private const val NOTIFICATION_CHANNEL_NAME = "OpenCode Server"
        private const val ACTION_START_SERVER = "com.logicedge.opencodemobile.START_SERVER"
        private const val ACTION_STOP_SERVER = "com.logicedge.opencodemobile.STOP_SERVER"
    }

    private val binder = LocalBinder()
    private lateinit var serverManager: OpenCodeServerManager
    private lateinit var bootstrapInstaller: BootstrapInstaller
    private val serviceScope = CoroutineScope(Dispatchers.Default)
    private var isServerRunning = false

    /**
     * Binder for local client communication.
     */
    inner class LocalBinder : Binder() {
        fun getService(): OpenCodeForegroundService = this@OpenCodeForegroundService
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "OpenCodeForegroundService created")
        serverManager = OpenCodeServerManager(this)
        bootstrapInstaller = BootstrapInstaller(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: action=${intent?.action}")

        when (intent?.action) {
            ACTION_STOP_SERVER -> {
                Log.i(TAG, "Stopping server")
                stopServerAndService()
                return START_NOT_STICKY
            }
            else -> {
                // Start or restart the server
                Log.i(TAG, "Starting server")
                startServerSequence()
            }
        }

        return START_STICKY
    }

    /**
     * Start the complete bootstrap + install + server launch sequence.
     */
    private fun startServerSequence() {
        serviceScope.launch {
            try {
                // Step 1: Extract bootstrap if needed
                Log.i(TAG, "Step 1: Extracting bootstrap...")
                if (!bootstrapInstaller.isBootstrapExtracted()) {
                    val result = bootstrapInstaller.extractBootstrap { progress ->
                        Log.d(TAG, "Bootstrap extraction progress: $progress%")
                        updateNotification("Extracting bootstrap: $progress%")
                    }
                    if (result is BootstrapInstaller.Result.Error) {
                        Log.e(TAG, "Bootstrap extraction failed: ${result.message}")
                        updateNotification("Bootstrap extraction failed")
                        return@launch
                    }
                } else {
                    Log.i(TAG, "Bootstrap already extracted")
                }

                // Step 2: Install OpenCode CLI if needed
                Log.i(TAG, "Step 2: Installing OpenCode CLI...")
                updateNotification("Installing OpenCode CLI...")
                val installResult = serverManager.installOpenCodeCLI()
                if (installResult is OpenCodeServerManager.Result.Error) {
                    Log.e(TAG, "OpenCode CLI installation failed: ${installResult.message}")
                    updateNotification("OpenCode CLI installation failed")
                    return@launch
                }
                Log.i(TAG, "OpenCode CLI installed successfully")

                // Step 3: Write OpenCode config
                Log.i(TAG, "Step 3: Writing OpenCode configuration...")
                updateNotification("Configuring OpenCode...")
                val configResult = serverManager.writeOpenCodeConfig()
                if (configResult is OpenCodeServerManager.Result.Error) {
                    Log.e(TAG, "Failed to write config: ${configResult.message}")
                    updateNotification("Configuration failed")
                    return@launch
                }

                // Step 4: Start OpenCode server
                Log.i(TAG, "Step 4: Starting OpenCode server...")
                updateNotification("Starting OpenCode server...")
                val startResult = serverManager.startServer()
                if (startResult is OpenCodeServerManager.Result.Error) {
                    Log.e(TAG, "Failed to start server: ${startResult.message}")
                    updateNotification("Failed to start server")
                    return@launch
                }

                Log.i(TAG, "OpenCode server started successfully")
                isServerRunning = true
                updateNotification("OpenCode server running (127.0.0.1:4096)")

                // Request battery optimization exemption on first run
                requestBatteryOptimizationExemption()

            } catch (e: Exception) {
                Log.e(TAG, "Server startup failed", e)
                updateNotification("Server startup failed: ${e.message}")
            }
        }
    }

    /**
     * Stop the server and this service.
     */
    private fun stopServerAndService() {
        serviceScope.launch {
            try {
                if (isServerRunning) {
                    Log.i(TAG, "Stopping OpenCode server...")
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

    /**
     * Create or get the notification channel (required for Android 8.0+).
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "OpenCode server service notification"
                enableLights(false)
                enableVibration(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
            Log.i(TAG, "Notification channel created: $NOTIFICATION_CHANNEL_ID")
        }
    }

    /**
     * Create the foreground service notification.
     */
    private fun createNotification(text: String = "OpenCode server running"): Notification {
        // Create stop action intent
        val stopIntent = Intent(this, OpenCodeForegroundService::class.java).apply {
            action = ACTION_STOP_SERVER
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("OpenCode")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // Replace with your app icon
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopPendingIntent
            )
            .build()
    }

    /**
     * Update the foreground notification with new text.
     */
    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Start the foreground service with initial notification.
     */
    private fun startForegroundService() {
        try {
            val notification = createNotification("OpenCode server starting...")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ requires type specification
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12-13
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    0
                )
            } else {
                // Android 11 and below
                startForeground(NOTIFICATION_ID, notification)
            }

            Log.i(TAG, "Foreground service started with notification")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
        }
    }

    /**
     * Request battery optimization exemption (shown in system UI on first request).
     */
    private fun requestBatteryOptimizationExemption() {
        val sharedPrefs = getSharedPreferences("opencode_prefs", MODE_PRIVATE)
        val exemptionRequested = sharedPrefs.getBoolean("battery_exemption_requested", false)

        if (!exemptionRequested) {
            try {
                val intent = Intent().apply {
                    action = android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    data = android.net.Uri.parse("package:$packageName")
                }
                if (intent.resolveActivity(packageManager) != null) {
                    // Note: Starting this requires user interaction; it will show a system dialog
                    // In a production app, you might trigger this from a user settings screen instead
                    Log.i(TAG, "Battery optimization exemption intent prepared (user must accept)")
                    sharedPrefs.edit().putBoolean("battery_exemption_requested", true).apply()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to request battery optimization exemption", e)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "Service bound")
        startForegroundService()
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "Service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.i(TAG, "OpenCodeForegroundService destroyed")
        serviceScope.launch {
            try {
                if (isServerRunning) {
                    serverManager.stopServer()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during service destruction", e)
            }
        }
        super.onDestroy()
    }

    /**
     * Public function to explicitly stop the server from MainActivity.
     */
    fun stopServer() {
        stopServerAndService()
    }

    /**
     * Public function to check if server is running.
     */
    fun isServerActive(): Boolean = isServerRunning
}
