package com.pocketagent.app.core

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
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

    fun chatStream(command: String, config: Map<String, String> = emptyMap()): Flow<String> = flow {
        val bodyJson = JSONObject().apply {
            put("message", command)
            if (config.isNotEmpty()) {
                put("config", JSONObject(config as Map<*, *>))
            }
        }.toString()
        val body = bodyJson.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$BASE_URL/chat")
            .header("Accept", "text/event-stream")
            .post(body)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            emit("[ERROR] HTTP ${response.code}")
            return@flow
        }
        val reader = BufferedReader(InputStreamReader(response.body?.byteStream()))
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val l = line ?: continue
            if (l.startsWith("data: ")) {
                val data = l.removePrefix("data: ")
                if (data == "[DONE]") break
                emit(data)
            }
        }
        reader.close()
    }

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

    // ─── 代码同步 ───────────────────────

    suspend fun triggerSync(): SyncResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/sync")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()
            val response = shortTimeoutClient.newCall(request).execute()
            if (response.isSuccessful) SyncResult.Ok(response.body?.string() ?: "")
            else SyncResult.Error("HTTP ${response.code}")
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: "连接失败")
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
}
