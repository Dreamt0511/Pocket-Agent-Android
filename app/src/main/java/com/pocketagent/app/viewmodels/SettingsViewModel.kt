package com.pocketagent.app.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketagent.app.agent.AgentBridge
import com.pocketagent.app.data.SettingsRepository
import kotlinx.coroutines.launch

data class SettingsUiState(
    val llmBaseUrl: String = "http://127.0.0.1:8080/v1",
    val llmApiKey: String = "dummy",
    val llmModel: String = "gelab-zero-4b-preview",
    val llmTemperature: Float = 0.7f,
    val llmMaxTokens: Int = 8000,
    val mcpServerUrl: String = "http://127.0.0.1:7474/mcp",
    val autoUpdate: Boolean = true,
    val showStatus: Boolean = true,
    val testResult: String = "",
    val testSuccess: Boolean = false
)

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val agentBridge: AgentBridge
) : ViewModel() {

    var uiState by mutableStateOf(SettingsUiState())
        private set

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            val settings = settingsRepository.getSettings()
            uiState = uiState.copy(
                llmBaseUrl = settings.llmBaseUrl,
                llmApiKey = settings.llmApiKey,
                llmModel = settings.llmModel,
                llmTemperature = settings.llmTemperature,
                llmMaxTokens = settings.llmMaxTokens,
                mcpServerUrl = settings.mcpServerUrl,
                autoUpdate = settings.autoUpdate,
                showStatus = settings.showStatus
            )
        }
    }

    fun updateLlmBaseUrl(value: String) {
        uiState = uiState.copy(llmBaseUrl = value)
    }

    fun updateLlmApiKey(value: String) {
        uiState = uiState.copy(llmApiKey = value)
    }

    fun updateLlmModel(value: String) {
        uiState = uiState.copy(llmModel = value)
    }

    fun updateLlmTemperature(value: Float) {
        uiState = uiState.copy(llmTemperature = value)
    }

    fun updateLlmMaxTokens(value: Int) {
        uiState = uiState.copy(llmMaxTokens = value)
    }

    fun updateMcpServerUrl(value: String) {
        uiState = uiState.copy(mcpServerUrl = value)
    }

    fun updateAutoUpdate(value: Boolean) {
        uiState = uiState.copy(autoUpdate = value)
    }

    fun updateShowStatus(value: Boolean) {
        uiState = uiState.copy(showStatus = value)
    }

    fun saveSettings() {
        viewModelScope.launch {
            val settings = com.pocketagent.app.data.Settings(
                llmBaseUrl = uiState.llmBaseUrl,
                llmApiKey = uiState.llmApiKey,
                llmModel = uiState.llmModel,
                llmTemperature = uiState.llmTemperature,
                llmMaxTokens = uiState.llmMaxTokens,
                mcpServerUrl = uiState.mcpServerUrl,
                autoUpdate = uiState.autoUpdate,
                showStatus = uiState.showStatus
            )
            settingsRepository.saveSettings(settings)

            // 更新 Agent 配置
            updateAgentConfig()

            uiState = uiState.copy(
                testResult = "配置已保存",
                testSuccess = true
            )
        }
    }

    fun resetToDefaults() {
        uiState = SettingsUiState()
        saveSettings()
    }

    fun testConnection() {
        viewModelScope.launch {
            uiState = uiState.copy(
                testResult = "正在测试连接...",
                testSuccess = false
            )

            try {
                // 测试 Agent 连接
                val connected = agentBridge.start()
                if (connected) {
                    uiState = uiState.copy(
                        testResult = "连接成功！Agent 已就绪",
                        testSuccess = true
                    )
                } else {
                    uiState = uiState.copy(
                        testResult = "连接失败，请检查配置",
                        testSuccess = false
                    )
                }
            } catch (e: Exception) {
                uiState = uiState.copy(
                    testResult = "连接错误: ${e.message}",
                    testSuccess = false
                )
            }
        }
    }

    private suspend fun updateAgentConfig() {
        // 构建配置 JSON
        val config = mapOf(
            "DEFAULT_LLM_BASE_URL" to uiState.llmBaseUrl,
            "LLM_API_KEY" to uiState.llmApiKey,
            "LLM_MODEL" to uiState.llmModel,
            "LLM_TEMPERATURE" to uiState.llmTemperature.toString(),
            "LLM_MAX_TOKENS" to uiState.llmMaxTokens.toString(),
            "MCP_SERVER_URL" to uiState.mcpServerUrl
        )

        // 通过 AgentBridge 更新配置
        // 这里需要实现 AgentBridge.updateConfig() 方法
    }
}