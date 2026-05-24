package com.pocketagent.app.core

import android.content.Context
import android.util.Log
import com.pocketagent.app.update.CodeSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 配置管理器 — 持久化 .env 配置
 *
 * 基于主项目 .env.example 格式，支持:
 *   KEY=VALUE
 *   # 注释
 *   KEY="value with spaces"
 */
object ConfigManager {
    private const val TAG = "ConfigManager"
    private const val CONFIG_FILE = ".env"

    private var context: Context? = null

    fun init(ctx: Context) {
        context = ctx.applicationContext
    }

    /**
     * 读取所有配置
     */
    suspend fun loadAll(): Map<String, String> = withContext(Dispatchers.IO) {
        val configFile = getConfigFile() ?: return@withContext emptyMap()
        if (!configFile.exists()) return@withContext loadDefaults()

        val config = mutableMapOf<String, String>()
        configFile.readLines().forEach { line ->
            parseLine(line)?.let { (key, value) ->
                config[key] = value
            }
        }
        config
    }

    /**
     * 保存配置
     */
    suspend fun saveAll(config: Map<String, String>) = withContext(Dispatchers.IO) {
        val configFile = getConfigFile() ?: return@withContext
        val content = buildString {
            config.forEach { (key, value) ->
                append("$key=$value\n")
            }
        }
        configFile.writeText(content)
        Log.i(TAG, "Config saved to ${configFile.absolutePath}")
    }

    /**
     * 获取单个配置值
     */
    suspend fun get(key: String, defaultValue: String = ""): String {
        val all = loadAll()
        return all[key] ?: defaultValue
    }

    /**
     * 设置单个配置值
     */
    suspend fun set(key: String, value: String) {
        val all = loadAll().toMutableMap()
        all[key] = value
        saveAll(all)
    }

    /**
     * 删除配置
     */
    suspend fun remove(key: String) {
        val all = loadAll().toMutableMap()
        all.remove(key)
        saveAll(all)
    }

    /**
     * 重置为默认配置
     */
    suspend fun resetToDefaults() {
        saveAll(loadDefaults())
    }

    // ─── 私有方法 ───────────────────────────────

    private fun getConfigFile(): File? {
        val ctx = context ?: return null
        // 优先写入 Python 运行时目录（与 stable_entry.py 读取的 .env 路径一致）
        return try {
            val runtimeDir = CodeSyncManager.getInstance().getRuntimeDir()
            File(runtimeDir, CONFIG_FILE)
        } catch (_: IllegalStateException) {
            // CodeSyncManager 尚未初始化，回退到 filesDir
            File(ctx.filesDir, CONFIG_FILE)
        }
    }

    private fun parseLine(line: String): Pair<String, String>? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return null

        val equalsIndex = trimmed.indexOf('=')
        if (equalsIndex < 0) return null

        val key = trimmed.substring(0, equalsIndex).trim()
        var value = trimmed.substring(equalsIndex + 1).trim()

        // 处理引号
        if (value.startsWith('"') && value.endsWith('"')) {
            value = value.substring(1, value.length - 1)
        } else if (value.startsWith('\'') && value.endsWith('\'')) {
            value = value.substring(1, value.length - 1)
        }

        return key to value
    }

    private fun loadDefaults(): Map<String, String> {
        return mapOf(
            // 主 Agent 模型配置
            "DEFAULT_LLM_BASE_URL" to "",
            "LLM_API_KEY" to "",
            "LLM_MODEL" to "",
            "LLM_TEMPERATURE" to "0.7",
            "LLM_MAX_TOKENS" to "8000",

            // 子 Agent (Executor) 模型配置 — 留空则继承主模型
            "EXECUTOR_LLM_BASE_URL" to "",
            "EXECUTOR_API_KEY" to "",
            "EXECUTOR_MODEL" to "",
            "EXECUTOR_TEMPERATURE" to "",
            "EXECUTOR_MAX_TOKENS" to "",

            // MCP
            "MCP_SERVER_URL" to "http://127.0.0.1:7474/mcp",

            // 高级 — Agent 运行参数 (config.py)
            "MAX_ITERATIONS" to "300",
            "RECURSION_LIMIT" to "600",
            "MAX_CONTEXT_TOKENS" to "128000"
        )
    }
}