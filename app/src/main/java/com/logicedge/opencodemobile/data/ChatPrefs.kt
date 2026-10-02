package com.logicedge.opencodemobile.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.chatDataStore by preferencesDataStore(name = "chat_settings")

class ChatPrefs(private val context: Context) {

    private val showThinkingKey = booleanPreferencesKey("show_model_thinking")

    val showThinking: Flow<Boolean> = context.chatDataStore.data.map { prefs ->
        prefs[showThinkingKey] ?: false
    }

    suspend fun setShowThinking(enabled: Boolean) {
        context.chatDataStore.edit { it[showThinkingKey] = enabled }
    }
}
