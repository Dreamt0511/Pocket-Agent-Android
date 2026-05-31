package com.pocketagent.app.update

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 主仓库 API 客户端 — 与 GitHub/GitLab 主仓库通信
 *
 * 职责：
 *  - 获取最新版本信息
 *  - 下载代码包
 *  - 查询 changelog
 *  - 上报使用统计（可选）
 *
 * 使用方式：
 *   val api = MainRepositoryAPI("https://github.com/xxx/pocket-agent")
 *   val version = api.getLatestVersion()
 *   val changelog = api.getChangelog("v2.1.0")
 */
class MainRepositoryAPI(private val repoBaseUrl: String) {

    companion object {
        private const val TAG = "RepoAPI"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val apiBaseUrl: String
        get() = repoBaseUrl
            .replace("https://github.com/", "https://api.github.com/repos/")
            .trimEnd('/')

    // ─── 版本信息 ─────────────────────────────────

    data class VersionInfo(
        val version: String,
        val codeVersion: String,   // Python 代码版本
        val apkVersion: String,    // APK 版本
        val changelog: String,
        val publishedAt: String,
        val minApkVersion: String  // 最低兼容 APK 版本
    )

    suspend fun getLatestVersionInfo(): VersionInfo? = withContext(Dispatchers.IO) {
        try {
            val response = get("$apiBaseUrl/releases/latest")
            if (!response.isSuccessful) return@withContext null

            val json = JSONObject(response.body?.string() ?: return@withContext null)
            val body = json.optString("body", "")

            VersionInfo(
                version = json.optString("tag_name", "0.0.0").trimStart('v'),
                codeVersion = extractField(body, "code_version") ?: json.optString("tag_name", "0.0.0").trimStart('v'),
                apkVersion = extractField(body, "apk_version") ?: json.optString("tag_name", "0.0.0").trimStart('v'),
                changelog = body,
                publishedAt = json.optString("published_at", ""),
                minApkVersion = extractField(body, "min_apk_version") ?: "1.0.0"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get version info", e)
            null
        }
    }

    /**
     * 直接从 release body 的 Markdown 中提取字段
     * 约定格式: `**code_version**: 2.1.0`
     */
    private fun extractField(body: String, field: String): String? {
        val regex = Regex("""\*\*${Regex.escape(field)}\*\*:\s*([^\s\n]+)""")
        return regex.find(body)?.groupValues?.getOrNull(1)
    }

    // ─── HTTP 封装 ──────────────────────────────

    private suspend fun get(url: String): okhttp3.Response {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/vnd.github.v3+json")
                .build()
            client.newCall(request).execute()
        }
    }
}