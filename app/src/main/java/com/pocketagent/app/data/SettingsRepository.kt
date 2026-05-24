package com.pocketagent.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.settingsDataStore by preferencesDataStore(name = "app_settings")

data class Settings(
    val llmBaseUrl: String = "http://127.0.0.1:8080/v1",
    val llmApiKey: String = "dummy",
    val llmModel: String = "gelab-zero-4b-preview",
    val llmTemperature: Float = 0.7f,
    val llmMaxTokens: Int = 8000,
    val mcpServerUrl: String = "http://127.0.0.1:7474/mcp",
    val autoUpdate: Boolean = true,
    val showStatus: Boolean = true
)

class SettingsRepository(
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        private val LLM_BASE_URL = stringPreferencesKey("llm_base_url")
        private val LLM_API_KEY = stringPreferencesKey("llm_api_key")
        private val LLM_MODEL = stringPreferencesKey("llm_model")
        private val LLM_TEMPERATURE = floatPreferencesKey("llm_temperature")
        private val LLM_MAX_TOKENS = intPreferencesKey("llm_max_tokens")
        private val MCP_SERVER_URL = stringPreferencesKey("mcp_server_url")
        private val AUTO_UPDATE = booleanPreferencesKey("auto_update")
        private val SHOW_STATUS = booleanPreferencesKey("show_status")
    }

    val settingsFlow: Flow<Settings> = dataStore.data
        .map { preferences ->
            Settings(
                llmBaseUrl = preferences[LLM_BASE_URL] ?: "http://127.0.0.1:8080/v1",
                llmApiKey = preferences[LLM_API_KEY] ?: "dummy",
                llmModel = preferences[LLM_MODEL] ?: "gelab-zero-4b-preview",
                llmTemperature = preferences[LLM_TEMPERATURE] ?: 0.7f,
                llmMaxTokens = preferences[LLM_MAX_TOKENS] ?: 8000,
                mcpServerUrl = preferences[MCP_SERVER_URL] ?: "http://127.0.0.1:7474/mcp",
                autoUpdate = preferences[AUTO_UPDATE] ?: true,
                showStatus = preferences[SHOW_STATUS] ?: true
            )
        }

    suspend fun getSettings(): Settings {
        return settingsFlow.first()
    }

    suspend fun saveSettings(settings: Settings) {
        dataStore.edit { preferences ->
            preferences[LLM_BASE_URL] = settings.llmBaseUrl
            preferences[LLM_API_KEY] = settings.llmApiKey
            preferences[LLM_MODEL] = settings.llmModel
            preferences[LLM_TEMPERATURE] = settings.llmTemperature
            preferences[LLM_MAX_TOKENS] = settings.llmMaxTokens
            preferences[MCP_SERVER_URL] = settings.mcpServerUrl
            preferences[AUTO_UPDATE] = settings.autoUpdate
            preferences[SHOW_STATUS] = settings.showStatus
        }
    }
}