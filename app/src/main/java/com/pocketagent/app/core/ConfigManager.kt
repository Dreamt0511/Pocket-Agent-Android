package com.pocketagent.app.core

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 配置管理器 — 通过 HTTP 读写 Termux 侧 .env
 */
object ConfigManager {
    private const val TAG = "ConfigManager"

    suspend fun ensureEnvFile(): Boolean = true

    suspend fun loadAll(): Map<String, String> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<String, String>()

        // 从 Termux 服务读取当前配置
        try {
            val remote = TermuxServiceClient.fetchConfig()
            result.putAll(remote)
        } catch (_: Exception) {}

        // 默认值补全
        loadDefaults().forEach { (k, v) -> result.putIfAbsent(k, v) }
        result
    }

    suspend fun saveAll(config: Map<String, String>) = withContext(Dispatchers.IO) {
        try {
            TermuxServiceClient.syncConfig(config)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync config to Termux: ${e.message}")
        }
    }

    private fun loadDefaults(): Map<String, String> {
        return mapOf(
            "DEFAULT_LLM_BASE_URL" to "",
            "LLM_API_KEY" to "",
            "LLM_MODEL" to "",
            "LLM_TEMPERATURE" to "0.7",
            "LLM_MAX_TOKENS" to "8000",
            "EXECUTOR_LLM_BASE_URL" to "",
            "EXECUTOR_API_KEY" to "",
            "EXECUTOR_MODEL" to "",
            "EXECUTOR_TEMPERATURE" to "",
            "EXECUTOR_MAX_TOKENS" to "",
            "MCP_SERVER_URL" to "http://127.0.0.1:7474/mcp",
            "EMBEDDING_MODEL_PATH" to "",
            "MAX_ITERATIONS" to "300",
            "RECURSION_LIMIT" to "600",
            "MAX_CONTEXT_TOKENS" to "128000",
        )
    }
}
