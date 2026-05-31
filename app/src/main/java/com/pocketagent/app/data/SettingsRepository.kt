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
    val llmBaseUrl: String = "",
    val llmApiKey: String = "",
    val llmModel: String = "",
    val llmTemperature: Float = 0.7f,
    val llmMaxTokens: Int = 8000,
    val mcpServerUrl: String = "http://127.0.0.1:7474/mcp",
    val autoUpdate: Boolean = true,
    val showStatus: Boolean = true,
    val pypiMirrorUrl: String = "",
    val embeddingModelPath: String = "",
    val executorBaseUrl: String = "",
    val executorApiKey: String = "",
    val executorModel: String = ""
)

class SettingsRepository(
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        private val DEFAULT_LLM_BASE_URL = stringPreferencesKey("llm_base_url")
        private val LLM_API_KEY = stringPreferencesKey("llm_api_key")
        private val LLM_MODEL = stringPreferencesKey("llm_model")
        private val LLM_TEMPERATURE = floatPreferencesKey("llm_temperature")
        private val LLM_MAX_TOKENS = intPreferencesKey("llm_max_tokens")
        private val MCP_SERVER_URL = stringPreferencesKey("mcp_server_url")
        private val AUTO_UPDATE = booleanPreferencesKey("auto_update")
        private val SHOW_STATUS = booleanPreferencesKey("show_status")
        private val PYPI_MIRROR_URL = stringPreferencesKey("pypi_mirror_url")
        private val SERVICE_STOP_REQUESTED = booleanPreferencesKey("service_stop_requested")
        private val INITIAL_SETUP_DONE = booleanPreferencesKey("initial_setup_done")
        private val ACTIVE_CONVERSATION_ID = stringPreferencesKey("active_conversation_id")
        private val DELETED_SKILLS = stringPreferencesKey("deleted_skills")
        private val MODIFIED_SKILLS = stringPreferencesKey("modified_skills")
        private val EMBEDDING_MODEL_PATH = stringPreferencesKey("embedding_model_path")
        private val EXECUTOR_BASE_URL = stringPreferencesKey("executor_base_url")
        private val EXECUTOR_API_KEY = stringPreferencesKey("executor_api_key")
        private val EXECUTOR_MODEL = stringPreferencesKey("executor_model")
    }

    val settingsFlow: Flow<Settings> = dataStore.data
        .map { preferences ->
            Settings(
                llmBaseUrl = preferences[DEFAULT_LLM_BASE_URL] ?: "",
                llmApiKey = preferences[LLM_API_KEY] ?: "",
                llmModel = preferences[LLM_MODEL] ?: "",
                llmTemperature = preferences[LLM_TEMPERATURE] ?: 0.7f,
                llmMaxTokens = preferences[LLM_MAX_TOKENS] ?: 8000,
                mcpServerUrl = preferences[MCP_SERVER_URL] ?: "http://127.0.0.1:7474/mcp",
                autoUpdate = preferences[AUTO_UPDATE] ?: true,
                showStatus = preferences[SHOW_STATUS] ?: true,
                pypiMirrorUrl = preferences[PYPI_MIRROR_URL] ?: "",
                embeddingModelPath = preferences[EMBEDDING_MODEL_PATH] ?: "",
                executorBaseUrl = preferences[EXECUTOR_BASE_URL] ?: "",
                executorApiKey = preferences[EXECUTOR_API_KEY] ?: "",
                executorModel = preferences[EXECUTOR_MODEL] ?: ""
            )
        }

    suspend fun getSettings(): Settings {
        return settingsFlow.first()
    }

    suspend fun saveSettings(settings: Settings) {
        dataStore.edit { preferences ->
            preferences[DEFAULT_LLM_BASE_URL] = settings.llmBaseUrl
            preferences[LLM_API_KEY] = settings.llmApiKey
            preferences[LLM_MODEL] = settings.llmModel
            preferences[LLM_TEMPERATURE] = settings.llmTemperature
            preferences[LLM_MAX_TOKENS] = settings.llmMaxTokens
            preferences[MCP_SERVER_URL] = settings.mcpServerUrl
            preferences[AUTO_UPDATE] = settings.autoUpdate
            preferences[SHOW_STATUS] = settings.showStatus
            preferences[PYPI_MIRROR_URL] = settings.pypiMirrorUrl
            preferences[EMBEDDING_MODEL_PATH] = settings.embeddingModelPath
            preferences[EXECUTOR_BASE_URL] = settings.executorBaseUrl
            preferences[EXECUTOR_API_KEY] = settings.executorApiKey
            preferences[EXECUTOR_MODEL] = settings.executorModel
        }
    }

    suspend fun isServiceStopRequested(): Boolean {
        return dataStore.data.first()[SERVICE_STOP_REQUESTED] ?: false
    }

    suspend fun setServiceStopRequested(value: Boolean) {
        dataStore.edit { it[SERVICE_STOP_REQUESTED] = value }
    }

    suspend fun isInitialSetupDone(): Boolean {
        return dataStore.data.first()[INITIAL_SETUP_DONE] ?: false
    }

    suspend fun setInitialSetupDone(value: Boolean) {
        dataStore.edit { it[INITIAL_SETUP_DONE] = value }
    }

    suspend fun getActiveConversationId(): String? {
        return dataStore.data.first()[ACTIVE_CONVERSATION_ID]?.ifBlank { null }
    }

    suspend fun setActiveConversationId(value: String?) {
        dataStore.edit {
            if (value.isNullOrBlank()) it.remove(ACTIVE_CONVERSATION_ID)
            else it[ACTIVE_CONVERSATION_ID] = value
        }
    }

    /** 获取已删除的技能路径集合（防止后端重新返回已删除的技能） */
    suspend fun getDeletedSkills(): Set<String> = getStringSet(DELETED_SKILLS)

    /** 记录技能已删除 */
    suspend fun addDeletedSkill(path: String) = addToStringSet(DELETED_SKILLS, path)

    /** 获取已修改的技能路径集合（主库更新时保留本地修改） */
    suspend fun getModifiedSkills(): Set<String> = getStringSet(MODIFIED_SKILLS)

    /** 记录技能已修改 */
    suspend fun addModifiedSkill(path: String) = addToStringSet(MODIFIED_SKILLS, path)

    private suspend fun getStringSet(key: Preferences.Key<String>): Set<String> {
        val json = dataStore.data.first()[key] ?: return emptySet()
        return try {
            org.json.JSONArray(json).let { arr ->
                (0 until arr.length()).map { arr.getString(it) }.toSet()
            }
        } catch (_: Exception) { emptySet() }
    }

    private suspend fun addToStringSet(key: Preferences.Key<String>, value: String) {
        val current = getStringSet(key).toMutableSet()
        current.add(value)
        val json = org.json.JSONArray(current.toList()).toString()
        dataStore.edit { it[key] = json }
    }
}
