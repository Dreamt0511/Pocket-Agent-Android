package com.pocketagent.app.core

import android.content.Context
import android.util.Log
import com.pocketagent.app.update.CodeSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 配置管理器 — 按归属分类持久化配置
 *
 * 配置分为两类，各自写入主库对应的文件，确保主库代码能读到：
 *   .env 类      → runtime/.env             （被 agent/core.py 的 load_dotenv 读取）
 *   config.py 类 → runtime/agent/config.py   （被 agent/core.py import）
 */
object ConfigManager {
    private const val TAG = "ConfigManager"
    private const val ENV_FILE = ".env"
    private const val ENV_EXAMPLE = ".env.example"
    private const val CONFIG_PY_FILE = "agent/config.py"

    /** 归属于 .env 的键 */
    private val ENV_KEYS = setOf(
        "DEFAULT_LLM_BASE_URL", "LLM_API_KEY", "LLM_MODEL",
        "LLM_TEMPERATURE", "LLM_MAX_TOKENS",
        "EXECUTOR_LLM_BASE_URL", "EXECUTOR_API_KEY", "EXECUTOR_MODEL",
        "EXECUTOR_TEMPERATURE", "EXECUTOR_MAX_TOKENS",
        "MCP_SERVER_URL",
    )

    /** 归属于 config.py 的键 */
    private val CONFIG_PY_KEYS = setOf(
        "MAX_ITERATIONS", "RECURSION_LIMIT", "MAX_CONTEXT_TOKENS",
    )

    private var context: Context? = null

    fun init(ctx: Context) {
        context = ctx.applicationContext
    }

    // ─── 公开接口 ─────────────────────────────────

    /**
     * 确保 .env 文件存在，不存在则从 .env.example 模板创建
     */
    suspend fun ensureEnvFile(): Boolean = withContext(Dispatchers.IO) {
        val envFile = getEnvFile() ?: return@withContext false
        if (envFile.exists()) return@withContext true

        // 优先从种子 assets 复制 .env.example
        val exampleFile = getExampleFile()
        if (exampleFile?.exists() == true) {
            exampleFile.copyTo(envFile, overwrite = false)
            Log.i(TAG, "Created .env from .env.example at ${envFile.absolutePath}")
            return@withContext true
        }

        // 兜底：写默认值
        val defaults = loadDefaults()
        val content = defaults.filterKeys { it in ENV_KEYS }
            .entries.joinToString("\n") { "${it.key}=${it.value}" }
        envFile.writeText(content)
        Log.i(TAG, "Created .env with defaults at ${envFile.absolutePath}")
        true
    }

    /**
     * 读取所有配置（合并 .env + config.py）
     */
    suspend fun loadAll(): Map<String, String> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<String, String>()

        // 从 .env 读取
        getEnvFile()?.let { envFile ->
            if (envFile.exists()) {
                envFile.readLines().forEach { line ->
                    parseLine(line)?.let { (k, v) -> result[k] = v }
                }
            }
        }

        // 从 config.py 读取 MAX_ITERATIONS / RECURSION_LIMIT / MAX_CONTEXT_TOKENS
        result.putAll(loadConfigPyValues())

        // 用默认值补全缺失项
        val defaults = loadDefaults()
        defaults.forEach { (k, v) -> result.putIfAbsent(k, v) }

        result
    }

    /**
     * 保存配置（自动按归属分类）
     * - .env 类 → runtime/.env
     * - config.py 类 → runtime/agent/config.py
     */
    suspend fun saveAll(config: Map<String, String>) = withContext(Dispatchers.IO) {
        saveEnvConfig(config)
        saveConfigPy(config)
    }

    suspend fun get(key: String, defaultValue: String = ""): String {
        val all = loadAll()
        return all[key] ?: defaultValue
    }

    suspend fun set(key: String, value: String) {
        val all = loadAll().toMutableMap()
        all[key] = value
        saveAll(all)
    }

    suspend fun remove(key: String) {
        val all = loadAll().toMutableMap()
        all.remove(key)
        saveAll(all)
    }

    suspend fun resetToDefaults() {
        saveAll(loadDefaults())
    }

    // ─── 分类保存 ─────────────────────────────────

    private suspend fun saveEnvConfig(config: Map<String, String>) {
        val envFile = getEnvFile() ?: return
        val content = config.filterKeys { it in ENV_KEYS }
            .entries.joinToString("\n") { "${it.key}=${it.value}" }
        envFile.writeText(content + "\n")
        Log.i(TAG, "Written ${config.count { it.key in ENV_KEYS }} env vars to ${envFile.absolutePath}")
    }

    private suspend fun saveConfigPy(config: Map<String, String>) {
        val configPyFile = getConfigPyFile() ?: return
        if (!configPyFile.exists()) {
            Log.w(TAG, "agent/config.py not found, skipping config.py write")
            return
        }

        val pyVars = config.filterKeys { it in CONFIG_PY_KEYS }
        if (pyVars.isEmpty()) return

        var content = configPyFile.readText()
        var changed = false

        for ((key, value) in pyVars) {
            val pattern = """^($key)\s*=\s*\d+""".toRegex(RegexOption.MULTILINE)
            if (pattern.containsMatchIn(content)) {
                content = content.replace(pattern, "$1 = $value")
                changed = true
            } else {
                // 键不存在则追加到文件末尾
                content += "\n$key = $value\n"
                changed = true
            }
        }

        if (changed) {
            configPyFile.writeText(content)
            Log.i(TAG, "Updated config.py vars: ${pyVars.keys}")
        }
    }

    // ─── 私有方法 ─────────────────────────────────

    private fun getRuntimeDir(): File? {
        return try {
            CodeSyncManager.getInstance().getRuntimeDir()
        } catch (_: IllegalStateException) {
            context?.let { File(it.filesDir, "app_python_runtime") }
        }
    }

    private fun getEnvFile(): File? {
        return getRuntimeDir()?.let { File(it, ENV_FILE) }
    }

    private fun getExampleFile(): File? {
        return getRuntimeDir()?.let { File(it, ENV_EXAMPLE) }
    }

    private fun getConfigPyFile(): File? {
        return getRuntimeDir()?.let { File(it, CONFIG_PY_FILE) }
    }

    /**
     * 从 agent/config.py 中读取 MAX_ITERATIONS / RECURSION_LIMIT / MAX_CONTEXT_TOKENS
     */
    private fun loadConfigPyValues(): Map<String, String> {
        val file = getConfigPyFile() ?: return emptyMap()
        if (!file.exists()) return emptyMap()

        val result = mutableMapOf<String, String>()
        val pattern = """^(MAX_ITERATIONS|RECURSION_LIMIT|MAX_CONTEXT_TOKENS)\s*=\s*(\d+)""".toRegex(RegexOption.MULTILINE)

        for (match in pattern.findAll(file.readText())) {
            result[match.groupValues[1]] = match.groupValues[2]
        }
        return result
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

    private fun loadDefaults(): Map<String, String> {
        return mapOf(
            // .env 类
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

            // config.py 类
            "MAX_ITERATIONS" to "300",
            "RECURSION_LIMIT" to "600",
            "MAX_CONTEXT_TOKENS" to "128000",
        )
    }
}
