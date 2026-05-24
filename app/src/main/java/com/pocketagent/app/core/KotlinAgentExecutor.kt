package com.pocketagent.app.core

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Kotlin 原生 Agent 执行器 — 不依赖 Python / Termux
 *
 * 直接通过 HTTP 调用 LLM API，支持流式输出。
 * 当 Python 运行时不可用时自动使用此降级方案。
 */
class KotlinAgentExecutor {

    companion object {
        private const val TAG = "KotlinAgent"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    /**
     * 执行用户指令，通过 LLM API 直接调用
     *
     * @param command 用户指令文本
     * @param config 配置 map（从 ConfigManager 读取）
     * @param onOutput 流式回调 — 每收到一段文本就调用
     * @return 完整响应文本
     */
    suspend fun execute(
        command: String,
        config: Map<String, String>,
        onOutput: (String) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {

        val baseUrl = config["LLM_BASE_URL"]?.trimEnd('/') ?: ""
        val apiKey = config["LLM_API_KEY"] ?: ""
        val model = config["LLM_MODEL"] ?: ""
        val temperature = config["LLM_TEMPERATURE"]?.toFloatOrNull() ?: 0.7f
        val maxTokens = config["LLM_MAX_TOKENS"]?.toIntOrNull() ?: 8000

        if (baseUrl.isBlank() || apiKey.isBlank()) {
            val msg = "请先在设置中配置 LLM API 地址和密钥"
            onOutput(msg)
            return@withContext msg
        }

        val apiUrl = if (baseUrl.endsWith("/v1")) "$baseUrl/chat/completions"
        else "$baseUrl/v1/chat/completions"

        onOutput("[info] 正在调用 $model ...\n")

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", "你是 Pocket Agent，一个手机 AI 助手。根据用户的指令，提供详细的步骤指导和操作建议。回答简洁、实用。")
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", command)
            })
        }

        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", temperature.toDouble())
            put("max_tokens", maxTokens)
            put("stream", true)
        }

        try {
            val request = Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorMsg = "API 返回错误 (${response.code}): ${response.body?.string()}"
                Log.e(TAG, errorMsg)
                onOutput("[error] $errorMsg")
                return@withContext errorMsg
            }

            val reader = BufferedReader(InputStreamReader(response.body?.byteStream()))
            val fullResponse = StringBuilder()

            reader.use { r ->
                r.forEachLine { line ->
                    if (line.startsWith("data: ")) {
                        val data = line.removePrefix("data: ")
                        if (data == "[DONE]") return@forEachLine
                        try {
                            val json = JSONObject(data)
                            val choices = json.optJSONArray("choices")
                            val delta = choices
                                ?.optJSONObject(0)
                                ?.optJSONObject("delta")
                            val content = delta?.optString("content") ?: ""
                            if (content.isNotBlank()) {
                                fullResponse.append(content)
                                onOutput(content)
                            }
                        } catch (_: Exception) {}
                    }
                }
            }

            val result = fullResponse.toString()
            if (result.isBlank()) {
                onOutput("[info] (空响应)")
            }
            result

        } catch (e: Exception) {
            val msg = "请求失败: ${e.message}"
            Log.e(TAG, msg, e)
            onOutput("[error] $msg")
            msg
        }
    }
}
