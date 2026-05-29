package com.pocketagent.app.core

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Termux FastAPI HTTP 客户端 — 与 Termux 中运行的 app.py 通信
 */
object TermuxServiceClient {
    private const val TAG = "TermuxServiceClient"
    private const val BASE_URL = "http://127.0.0.1:8000"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // SSE 无超时
        .build()

    private val shortTimeoutClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val longPollClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    // 当前 SSE 请求，用于取消
    @Volatile
    private var currentCall: Call? = null

    /** 取消当前正在进行的 SSE 请求，同时通知 Python 端取消（包括子 Agent） */
    fun cancelChat() {
        val call = currentCall
        currentCall = null
        call?.cancel()
        // 通知 Python 端取消执行
        try {
            val body = "{}".toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url("$BASE_URL/cancel").post(body).build()
            shortTimeoutClient.newCall(request).execute().close()
        } catch (_: Exception) {}
    }

    // ─── 健康检查 ───────────────────────

    suspend fun healthCheck(): HealthResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$BASE_URL/health").build()
            val response = shortTimeoutClient.newCall(request).execute()
            if (response.isSuccessful) {
                HealthResult.Ok(response.body?.string() ?: "")
            } else {
                HealthResult.Error("HTTP ${response.code}")
            }
        } catch (e: Exception) {
            HealthResult.Error(e.message ?: "连接失败")
        }
    }

    // ─── 获取服务启动时间戳 ─────────────────
    // 只请求一次，App 端自己累加运行时长

    suspend fun fetchStartTime(): Long = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$BASE_URL/uptime").build()
            val response = shortTimeoutClient.newCall(request).execute()
            if (response.isSuccessful) {
                val json = org.json.JSONObject(response.body?.string() ?: "{}")
                json.optLong("started_at", 0).toLong()
            } else {
                0
            }
        } catch (_: Exception) {
            0
        }
    }

    // ─── 轮询等待服务就绪 ─────────────────
    // pip install 在手机上可能耗时 2-5 分钟，持续重试直到 uvicorn 响应

    data class WaitResult(val success: Boolean, val message: String)

    suspend fun waitForService(
        maxAttempts: Int = 60,
        intervalMs: Long = 5000L,
        onAttempt: (attempt: Int, total: Int, lastError: String, elapsedSec: Long) -> Unit = { _, _, _, _ -> }
    ): WaitResult = withContext(Dispatchers.IO) {
        var lastError = ""
        val startMs = System.currentTimeMillis()
        for (i in 1..maxAttempts) {
            val elapsed = (System.currentTimeMillis() - startMs) / 1000
            try {
                val request = Request.Builder().url("$BASE_URL/health").build()
                val response = longPollClient.newCall(request).execute()
                if (response.isSuccessful) {
                    onAttempt(i, maxAttempts, "已就绪", elapsed)
                    return@withContext WaitResult(true, response.body?.string() ?: "ok")
                } else {
                    lastError = "HTTP ${response.code}"
                }
            } catch (e: Exception) {
                lastError = e.message ?: "连接失败"
            }
            if (i < maxAttempts) {
                onAttempt(i, maxAttempts, lastError, elapsed)
                kotlinx.coroutines.delay(intervalMs)
            } else {
                onAttempt(i, maxAttempts, lastError, elapsed)
            }
        }
        return@withContext WaitResult(false, lastError)
    }

    // ─── 安装依赖 ───────────────────────

    suspend fun setup(): SetupResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/setup")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()
            val response = shortTimeoutClient.newCall(request).execute()
            if (response.isSuccessful) {
                SetupResult.Ok(response.body?.string() ?: "")
            } else {
                SetupResult.Error(response.body?.string() ?: "HTTP ${response.code}")
            }
        } catch (e: Exception) {
            SetupResult.Error(e.message ?: "连接失败")
        }
    }

    // ─── SSE 流式执行 ───────────────────

    fun chatStream(command: String, config: Map<String, String> = emptyMap(), conversationId: String? = null): Flow<String> = flow {
        val bodyJson = JSONObject().apply {
            put("message", command)
            if (config.isNotEmpty()) {
                put("config", JSONObject(config as Map<*, *>))
            }
            if (conversationId != null) {
                put("conversation_id", conversationId)
            }
        }.toString()
        val body = bodyJson.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$BASE_URL/chat")
            .header("Accept", "text/event-stream")
            .post(body)
            .build()

        val call = client.newCall(request)
        currentCall = call
        val response = try {
            call.execute()
        } catch (e: Exception) {
            currentCall = null
            throw e
        }
        if (!response.isSuccessful) {
            currentCall = null
            emit("[ERROR] HTTP ${response.code}")
            return@flow
        }
        val reader = BufferedReader(InputStreamReader(response.body?.byteStream()))
        try {
            var line: String?
            var dataBuffer = StringBuilder()
            var inData = false
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                if (l.startsWith("data: ")) {
                    // 新的 data 行开始，先 emit 之前累积的数据
                    if (inData && dataBuffer.isNotEmpty()) {
                        val data = dataBuffer.toString()
                        if (data == "[DONE]") break
                        emit(data)
                    }
                    dataBuffer = StringBuilder(l.removePrefix("data: "))
                    inData = true
                } else if (inData && l.isNotEmpty()) {
                    // SSE 数据中的续行（JSON 内含换行符时会被 readLine 拆成多行）
                    dataBuffer.append("\n").append(l)
                } else if (l.isEmpty()) {
                    // 空行 = SSE 事件结束
                    if (inData && dataBuffer.isNotEmpty()) {
                        val data = dataBuffer.toString()
                        if (data == "[DONE]") break
                        emit(data)
                        dataBuffer = StringBuilder()
                        inData = false
                    }
                }
            }
            // 处理最后一条未以空行结束的数据
            if (inData && dataBuffer.isNotEmpty()) {
                val data = dataBuffer.toString()
                if (data != "[DONE]") emit(data)
            }
        } finally {
            reader.close()
            currentCall = null
        }
    }.flowOn(Dispatchers.IO)

    // ─── 配置同步 ───────────────────────

    suspend fun syncConfig(config: Map<String, String>): ConfigResult = withContext(Dispatchers.IO) {
        try {
            val json = config.entries.joinToString(",", "{", "}") {
                "\"${it.key}\": \"${it.value.replace("\"", "\\\"")}\""
            }
            val request = Request.Builder()
                .url("$BASE_URL/config")
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()
            val response = shortTimeoutClient.newCall(request).execute()
            if (response.isSuccessful) ConfigResult.Ok
            else ConfigResult.Error("HTTP ${response.code}")
        } catch (e: Exception) {
            ConfigResult.Error(e.message ?: "连接失败")
        }
    }

    /** 检查嵌入模型服务是否运行 */
    suspend fun checkEmbedding(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("http://127.0.0.1:8080/health").build()
            val response = shortTimeoutClient.newCall(request).execute()
            response.isSuccessful
        } catch (_: Exception) { false }
    }

    /** 从 Termux 服务读取当前配置 */
    suspend fun fetchConfig(): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$BASE_URL/config").build()
            val response = shortTimeoutClient.newCall(request).execute()
            if (response.isSuccessful) {
                val obj = org.json.JSONObject(response.body?.string() ?: "{}")
                obj.keys().asSequence().associateWith { obj.optString(it, "") }
            } else emptyMap()
        } catch (_: Exception) { emptyMap() }
    }

    // ─── 关闭服务 ───────────────────────

    /**
     * HTTP 关闭 FastAPI 服务（比 TermuxLauncher.stopFastAPI 更可靠）
     */
    suspend fun shutdown(): ShutdownResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/shutdown")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()
            val response = shortTimeoutClient.newCall(request).execute()
            ShutdownResult.Ok
        } catch (e: Exception) {
            // 服务已关闭或无法连接也算成功
            ShutdownResult.Ok
        }
    }

    // ─── 技能 CRUD ──────────────────────

    suspend fun fetchSkills(): SkillsResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$BASE_URL/skills").build()
            val response = shortTimeoutClient.newCall(request).execute()
            if (response.isSuccessful) {
                SkillsResult.Ok(response.body?.string() ?: "{}")
            } else {
                SkillsResult.Error("HTTP ${response.code}")
            }
        } catch (e: Exception) {
            SkillsResult.Error(e.message ?: "连接失败")
        }
    }

    suspend fun createSkill(name: String, description: String, content: String, category: String): SkillCrudResult =
        withContext(Dispatchers.IO) {
            try {
                val json = org.json.JSONObject().apply {
                    put("name", name)
                    put("description", description)
                    put("content", content)
                    put("category", category)
                }
                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url("$BASE_URL/skills").post(body).build()
                val response = shortTimeoutClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val respBody = org.json.JSONObject(response.body?.string() ?: "{}")
                    SkillCrudResult.Ok(respBody.optString("path", ""))
                } else {
                    val err = try { org.json.JSONObject(response.body?.string() ?: "{}").optString("error") } catch (_: Exception) { "HTTP ${response.code}" }
                    SkillCrudResult.Error(err)
                }
            } catch (e: Exception) {
                SkillCrudResult.Error(e.message ?: "连接失败")
            }
        }

    suspend fun updateSkill(path: String, name: String, description: String, content: String): SkillCrudResult =
        withContext(Dispatchers.IO) {
            try {
                val json = org.json.JSONObject().apply {
                    put("name", name)
                    put("description", description)
                    put("content", content)
                }
                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url("$BASE_URL/skills/$path").put(body).build()
                val response = shortTimeoutClient.newCall(request).execute()
                if (response.isSuccessful) SkillCrudResult.Ok("")
                else {
                    val err = try { org.json.JSONObject(response.body?.string() ?: "{}").optString("error") } catch (_: Exception) { "HTTP ${response.code}" }
                    SkillCrudResult.Error(err)
                }
            } catch (e: Exception) {
                SkillCrudResult.Error(e.message ?: "连接失败")
            }
        }

    suspend fun deleteSkill(path: String): SkillCrudResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$BASE_URL/skills/$path").delete().build()
            val response = shortTimeoutClient.newCall(request).execute()
            if (response.isSuccessful) SkillCrudResult.Ok("")
            else {
                val err = try { org.json.JSONObject(response.body?.string() ?: "{}").optString("error") } catch (_: Exception) { "HTTP ${response.code}" }
                SkillCrudResult.Error(err)
            }
        } catch (e: Exception) {
            SkillCrudResult.Error(e.message ?: "连接失败")
        }
    }

    sealed class SkillCrudResult {
        data class Ok(val path: String) : SkillCrudResult()
        data class Error(val message: String) : SkillCrudResult()
    }

    // ─── 代码同步 ───────────────────────

    suspend fun triggerSync(mirrorUrl: String = ""): SyncResult = withContext(Dispatchers.IO) {
        try {
            val json = org.json.JSONObject().apply {
                put("mirror", mirrorUrl)
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$BASE_URL/sync")
                .post(body)
                .build()
            val response = shortTimeoutClient.newCall(request).execute()
            if (response.isSuccessful) SyncResult.Ok(response.body?.string() ?: "")
            else SyncResult.Error("HTTP ${response.code}")
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: "连接失败")
        }
    }

    /** 心跳 — 告知服务端 App 还活着 */
    suspend fun heartbeat() = withContext(Dispatchers.IO) {
        try {
            val body = "{}".toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url("$BASE_URL/heartbeat").post(body).build()
            shortTimeoutClient.newCall(request).execute().close()
        } catch (_: Exception) {}
    }

    /** 从 Termux 服务获取当前代码版本（git commit SHA） */
    suspend fun fetchVersion(): String = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$BASE_URL/version").build()
            val response = shortTimeoutClient.newCall(request).execute()
            if (response.isSuccessful) {
                org.json.JSONObject(response.body?.string() ?: "{}").optString("version", "")
            } else ""
        } catch (_: Exception) { "" }
    }

    /** 从 Termux 服务获取版本历史 */
    suspend fun fetchVersionHistory(): List<VersionEntry> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$BASE_URL/version/history").build()
            val response = shortTimeoutClient.newCall(request).execute()
            if (response.isSuccessful) {
                val arr = org.json.JSONObject(response.body?.string() ?: "{}").optJSONArray("history") ?: return@withContext emptyList()
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    VersionEntry(
                        sha = obj.optString("sha", ""),
                        message = obj.optString("message", ""),
                        timestamp = obj.optLong("timestamp", 0)
                    )
                }
            } else emptyList()
        } catch (_: Exception) { emptyList() }
    }

    /** 回退到指定版本 */
    suspend fun rollbackVersion(sha: String): RollbackResult = withContext(Dispatchers.IO) {
        try {
            val json = org.json.JSONObject().apply { put("sha", sha) }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url("$BASE_URL/version/rollback").post(body).build()
            val response = shortTimeoutClient.newCall(request).execute()
            if (response.isSuccessful) {
                val resp = org.json.JSONObject(response.body?.string() ?: "{}")
                if (resp.optString("status") == "ok") RollbackResult.Ok(resp.optString("version", sha))
                else RollbackResult.Error(resp.optString("message", "回退失败"))
            } else RollbackResult.Error("HTTP ${response.code}")
        } catch (e: Exception) {
            RollbackResult.Error(e.message ?: "连接失败")
        }
    }

    data class VersionEntry(val sha: String, val message: String, val timestamp: Long)

    sealed class RollbackResult {
        data class Ok(val version: String) : RollbackResult()
        data class Error(val message: String) : RollbackResult()
    }

    // ─── 会话管理 API ────────────────────

    suspend fun fetchConversations(): ConversationsResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$BASE_URL/conversations").build()
            val response = shortTimeoutClient.newCall(request).execute()
            if (response.isSuccessful) {
                ConversationsResult.Ok(response.body?.string() ?: "[]")
            } else {
                ConversationsResult.Error("HTTP ${response.code}")
            }
        } catch (e: Exception) {
            ConversationsResult.Error(e.message ?: "连接失败")
        }
    }

    suspend fun fetchMessages(conversationId: String): MessagesResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/conversations/$conversationId/messages")
                .build()
            val response = shortTimeoutClient.newCall(request).execute()
            if (response.isSuccessful) {
                MessagesResult.Ok(response.body?.string() ?: "[]")
            } else {
                MessagesResult.Error("HTTP ${response.code}")
            }
        } catch (e: Exception) {
            MessagesResult.Error(e.message ?: "连接失败")
        }
    }

    suspend fun deleteConversation(conversationId: String): DeleteResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/conversations/$conversationId")
                .delete()
                .build()
            val response = shortTimeoutClient.newCall(request).execute()
            if (response.isSuccessful) DeleteResult.Ok
            else DeleteResult.Error("HTTP ${response.code}")
        } catch (e: Exception) {
            DeleteResult.Error(e.message ?: "连接失败")
        }
    }

    // ─── 结果类型 ───────────────────────

    sealed class HealthResult {
        data class Ok(val body: String) : HealthResult()
        data class Error(val message: String) : HealthResult()
    }
    sealed class SetupResult {
        data class Ok(val body: String) : SetupResult()
        data class Error(val message: String) : SetupResult()
    }
    sealed class ConfigResult {
        object Ok : ConfigResult()
        data class Error(val message: String) : ConfigResult()
    }
    sealed class SyncResult {
        data class Ok(val body: String) : SyncResult()
        data class Error(val message: String) : SyncResult()
    }
    sealed class ShutdownResult {
        object Ok : ShutdownResult()
    }
    sealed class SkillsResult {
        data class Ok(val body: String) : SkillsResult()
        data class Error(val message: String) : SkillsResult()
    }
    sealed class ConversationsResult {
        data class Ok(val json: String) : ConversationsResult()
        data class Error(val message: String) : ConversationsResult()
    }
    sealed class MessagesResult {
        data class Ok(val json: String) : MessagesResult()
        data class Error(val message: String) : MessagesResult()
    }
    sealed class DeleteResult {
        object Ok : DeleteResult()
        data class Error(val message: String) : DeleteResult()
    }
}
