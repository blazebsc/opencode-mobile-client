package com.logicedge.opencodemobile.notify

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.notifyDataStore by preferencesDataStore(name = "opencode_native_notifications")

data class NotifyPrefs(
    val enabled: Boolean = false,
    val settingsConfigured: Boolean = false,
    val customPromptAnswered: Boolean = false,
)

class NotificationPrefs(private val context: Context) {

    private val enabledKey = booleanPreferencesKey("enabled")
    private val settingsConfiguredKey = booleanPreferencesKey("settingsConfigured")
    private val promptAnsweredKey = booleanPreferencesKey("customPromptAnswered")
    private val lastIdKey = longPreferencesKey("lastNotificationId")

    val prefs: Flow<NotifyPrefs> = context.notifyDataStore.data.map { p ->
        NotifyPrefs(
            enabled = p[enabledKey] ?: false,
            settingsConfigured = p[settingsConfiguredKey] ?: false,
            customPromptAnswered = p[promptAnsweredKey] ?: false,
        )
    }

    suspend fun load(): NotifyPrefs = prefs.first()

    suspend fun save(prefs: NotifyPrefs) {
        context.notifyDataStore.edit {
            it[enabledKey] = prefs.enabled
            it[settingsConfiguredKey] = prefs.settingsConfigured
            it[promptAnsweredKey] = prefs.customPromptAnswered
        }
    }

    suspend fun nextId(): Int {
        var next = 1
        context.notifyDataStore.edit { p ->
            val last = p[lastIdKey] ?: (System.currentTimeMillis() % Int.MAX_VALUE)
            next = ((last % Int.MAX_VALUE) + 1).toInt()
            p[lastIdKey] = next.toLong()
        }
        return next
    }
}

enum class NotifyDecision { EMIT, IGNORE, ASK }

fun decideNotify(prefs: NotifyPrefs): NotifyDecision = when {
    prefs.enabled -> NotifyDecision.EMIT
    prefs.settingsConfigured || prefs.customPromptAnswered -> NotifyDecision.IGNORE
    else -> NotifyDecision.ASK
}
