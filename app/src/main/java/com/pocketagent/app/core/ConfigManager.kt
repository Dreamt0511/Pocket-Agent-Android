package com.pocketagent.app.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 配置管理器 — 本地文件作缓存，HTTP 同步到 Termux
 */
object ConfigManager {
    private const val TAG = "ConfigManager"
    private const val ENV_FILE = ".env"
    private const val CONFIG_PY_FILE = "agent/config.py"

    private val ENV_KEYS = setOf(
        "DEFAULT_LLM_BASE_URL", "LLM_API_KEY", "LLM_MODEL",
        "LLM_TEMPERATURE", "LLM_MAX_TOKENS",
        "EXECUTOR_LLM_BASE_URL", "EXECUTOR_API_KEY", "EXECUTOR_MODEL",
        "EXECUTOR_TEMPERATURE", "EXECUTOR_MAX_TOKENS",
        "MCP_SERVER_URL",
    )

    private val CONFIG_PY_KEYS = setOf(
        "MAX_ITERATIONS", "RECURSION_LIMIT", "MAX_CONTEXT_TOKENS",
    )

    private var context: Context? = null

    fun init(ctx: Context) {
        context = ctx.applicationContext
    }

    // ─── 公开接口 ─────────────────────────────────

    /** 确保 .env 存在（现在 Termux 侧管理，App 侧返回 true 即可） */
    suspend fun ensureEnvFile(): Boolean = true

    suspend fun loadAll(): Map<String, String> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<String, String>()
        val runtimeDir = getRuntimeDir()

        // 从 .env 读取（安全：文件不存在则跳过）
        if (runtimeDir != null) {
            try {
                val envFile = File(runtimeDir, ENV_FILE)
                if (envFile.exists()) {
                    envFile.readLines().forEach { line ->
                        parseLine(line)?.let { (k, v) -> result[k] = v }
                    }
                }
            } catch (_: Exception) {}
        }

        // 默认值补全
        loadDefaults().forEach { (k, v) -> result.putIfAbsent(k, v) }
        result
    }

    suspend fun saveAll(config: Map<String, String>) = withContext(Dispatchers.IO) {
        // 本地 .env 写入（best-effort，权限不足则跳过）
        try {
            val runtimeDir = getRuntimeDir()
            if (runtimeDir != null) {
                val envFile = File(runtimeDir, ENV_FILE)
                val content = config.filterKeys { it in ENV_KEYS }
                    .entries.joinToString("\n") { "${it.key}=${it.value}" }
                envFile.writeText(content + "\n")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot write local .env (storage permission?), syncing to Termux only")
        }

        // HTTP 同步到 Termux（核心）
        try {
            TermuxServiceClient.syncConfig(config)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync config to Termux: ${e.message}")
        }
    }

    // ─── 路径（安全：权限不足时返回 null） ────────

    private fun getRuntimeDir(): File? {
        return try {
            // 优先读 /sdcard/Pocket-Agent/（Termux 共享路径）
            val dir = File("/sdcard/Pocket-Agent")
            if (!dir.exists()) dir.mkdirs()
            if (dir.exists() && dir.canWrite()) dir else null
        } catch (_: Exception) {
            null
        }
    }

    // ─── 默认值 ─────────────────────────────────

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
            "MAX_ITERATIONS" to "300",
            "RECURSION_LIMIT" to "600",
            "MAX_CONTEXT_TOKENS" to "128000",
        )
    }

    private fun parseLine(line: String): Pair<String, String>? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
        val equalsIndex = trimmed.indexOf('=')
        if (equalsIndex < 0) return null
        val key = trimmed.substring(0, equalsIndex).trim()
        var value = trimmed.substring(equalsIndex + 1).trim()
        if (value.startsWith('"') && value.endsWith('"')) {
            value = value.substring(1, value.length - 1)
        } else if (value.startsWith('\'') && value.endsWith('\'')) {
            value = value.substring(1, value.length - 1)
        }
        return key to value
    }
}
