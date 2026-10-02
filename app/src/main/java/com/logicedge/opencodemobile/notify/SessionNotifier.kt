package com.logicedge.opencodemobile.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.logicedge.opencodemobile.MainActivity

class SessionNotifier(
    private val context: Context,
    private val prefs: NotificationPrefs,
) {
    companion object {
        const val CHANNEL_ID = "opencode-web-notifications"
        const val MAX_BODY_PREVIEW = 140
    }

    private val shownIds = mutableMapOf<String, Int>()
    private val lock = Any()

    fun hasOsPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OpenCode session updates",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Agent progress for the connected OpenCode session"
                enableVibration(true)
            }
            context.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    suspend fun maybeNotify(title: String, body: String, tag: String? = null): Boolean {
        if (title.isBlank() || body.isBlank()) return false
        if (decideNotify(prefs.load()) != NotifyDecision.EMIT) return false
        if (!hasOsPermission()) return false
        ensureChannel()
        val openIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body.take(MAX_BODY_PREVIEW))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val id = prefs.nextId()
        NotificationManagerCompat.from(context).notify(tag, id, notification)
        if (tag != null) {
            synchronized(lock) { shownIds[tag] = id }
        }
        return true
    }

    fun cancel(tag: String) {
        val id = synchronized(lock) { shownIds.remove(tag) } ?: return
        NotificationManagerCompat.from(context).cancel(tag, id)
    }
}
