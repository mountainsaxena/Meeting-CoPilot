package com.example.meetingcopilot

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsManager(private val context: Context) {
    companion object {
        val OPENAI_API_KEY = stringPreferencesKey("openai_api_key")
        val CLAUDE_API_KEY = stringPreferencesKey("claude_api_key")
        val SELECTED_PROVIDER = stringPreferencesKey("selected_provider") // "OpenAI" or "Claude"
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
    }

    val openaiKeyFlow: Flow<String?> = context.dataStore.data.map { it[OPENAI_API_KEY] }
    val claudeKeyFlow: Flow<String?> = context.dataStore.data.map { it[CLAUDE_API_KEY] }
    val selectedProviderFlow: Flow<String> = context.dataStore.data.map { it[SELECTED_PROVIDER] ?: "OpenAI" }
    val keepScreenOnFlow: Flow<Boolean> = context.dataStore.data.map { it[KEEP_SCREEN_ON] ?: false }

    suspend fun saveOpenAIKey(key: String) {
        context.dataStore.edit { it[OPENAI_API_KEY] = key }
    }

    suspend fun saveClaudeKey(key: String) {
        context.dataStore.edit { it[CLAUDE_API_KEY] = key }
    }

    suspend fun setProvider(provider: String) {
        context.dataStore.edit { it[SELECTED_PROVIDER] = provider }
    }

    suspend fun setKeepScreenOn(enabled: Boolean) {
        context.dataStore.edit { it[KEEP_SCREEN_ON] = enabled }
    }

    suspend fun deleteApiKey(isOpenAI: Boolean) {
        context.dataStore.edit { 
            if (isOpenAI) it.remove(OPENAI_API_KEY) else it.remove(CLAUDE_API_KEY)
        }
    }
}
