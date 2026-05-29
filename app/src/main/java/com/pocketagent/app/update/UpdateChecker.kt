package com.pocketagent.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * 更新检查器 — 管理 APK 自身更新
 *
 * 代码更新由用户通过设置页手动触发 Termux /sync 端点完成。
 * 此类仅负责 APK 版本检查和下载安装。
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private lateinit var appContext: Context
    private lateinit var repoBaseUrl: String

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val _updateEvents = MutableStateFlow<UpdateEvent>(UpdateEvent.Idle)
    val updateEvents: StateFlow<UpdateEvent> = _updateEvents

    sealed class UpdateEvent {
        object Idle : UpdateEvent()
        object Checking : UpdateEvent()
        data class AppUpdateAvailable(val newVersion: String, val downloadUrl: String, val size: Long) : UpdateEvent()
        data class Error(val message: String) : UpdateEvent()
        object UpToDate : UpdateEvent()
    }

    fun init(context: Context, repoUrl: String) {
        appContext = context.applicationContext
        repoBaseUrl = repoUrl
    }

    /**
     * 检查所有更新（仅 APK 更新，代码更新由用户通过设置页手动触发 Termux /sync）
     */
    suspend fun checkAll(): UpdateEvent = withContext(Dispatchers.IO) {
        _updateEvents.value = UpdateEvent.Checking

        try {
            // 检查 APK 更新
            val appUpdate = checkAppUpdate()
            if (appUpdate != null) {
                _updateEvents.value = appUpdate
                return@withContext appUpdate
            }

            _updateEvents.value = UpdateEvent.UpToDate
            _updateEvents.value

        } catch (e: Exception) {
            Log.e(TAG, "Update check failed", e)
            UpdateEvent.Error(e.message ?: "检查失败").also {
                _updateEvents.value = it
            }
        }
    }

    // ─── APK 更新检查 ────────────────────────────

    private suspend fun checkAppUpdate(): UpdateEvent.AppUpdateAvailable? {
        return try {
            val apiUrl = repoBaseUrl
                .replace("https://github.com/", "https://api.github.com/repos/")
                .trimEnd('/') + "/releases/latest"

            val request = Request.Builder()
                .url(apiUrl)
                .addHeader("Accept", "application/vnd.github.v3+json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null

            val json = JSONObject(response.body?.string() ?: return null)
            val remoteVersion = json.optString("tag_name", "").trimStart('v')

            // 比较版本
            val localVersion = getAppVersion()
            if (!isNewerVersion(remoteVersion, localVersion)) return null

            // 查找 APK 附件
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                if (name.endsWith(".apk")) {
                    return UpdateEvent.AppUpdateAvailable(
                        newVersion = remoteVersion,
                        downloadUrl = asset.optString("browser_download_url", ""),
                        size = asset.optLong("size", 0)
                    )
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "App update check failed", e)
            null
        }
    }

    /**
     * 下载并安装新 APK
     */
    suspend fun downloadAndInstallApk(
        downloadUrl: String,
        onProgress: (Float) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val apkFile = File(appContext.cacheDir, "update.apk")
            val request = Request.Builder().url(downloadUrl).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) return@withContext false

            val body = response.body ?: return@withContext false
            val totalBytes = body.contentLength()
            var downloadedBytes = 0L

            body.byteStream().use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (totalBytes > 0) {
                            onProgress(downloadedBytes.toFloat() / totalBytes)
                        }
                    }
                }
            }

            // 触发安装
            installApk(apkFile)
            true
        } catch (e: Exception) {
            Log.e(TAG, "APK download failed", e)
            false
        }
    }

    private fun installApk(apkFile: File) {
        val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                apkFile
            )
        } else {
            Uri.fromFile(apkFile)
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(intent)
    }

    // ─── 工具方法 ────────────────────────────────

    private fun getAppVersion(): String {
        return try {
            val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            info.versionName ?: "0.0.0"
        } catch (_: Exception) {
            "0.0.0"
        }
    }

    /** 简单语义版本比较 */
    private fun isNewerVersion(remote: String, local: String): Boolean {
        try {
            val rParts = remote.split(".").map { it.toIntOrNull() ?: 0 }
            val lParts = local.split(".").map { it.toIntOrNull() ?: 0 }
            val maxLen = maxOf(rParts.size, lParts.size)
            for (i in 0 until maxLen) {
                val r = rParts.getOrElse(i) { 0 }
                val l = lParts.getOrElse(i) { 0 }
                if (r > l) return true
                if (r < l) return false
            }
            return false
        } catch (_: Exception) {
            return remote != local
        }
    }
}