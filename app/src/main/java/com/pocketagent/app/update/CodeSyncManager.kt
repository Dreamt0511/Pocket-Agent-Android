package com.pocketagent.app.update

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * 代码同步引擎 — 从主仓库动态拉取最新 Agent Python 代码
 *
 * 从 GitHub Archive API 下载整个仓库的 ZIP，提取 agent/ 目录到运行时目录。
 */
class CodeSyncManager(private val context: Context) {

    companion object {
        private const val TAG = "CodeSyncManager"
        private const val GITHUB_REPO = "Dreamt0511/Pocket-Agent"
        private const val GITHUB_BRANCH = "main"
        private const val RUNTIME_DIR = "app_python_runtime"
        private const val VERSION_FILE = "version.json"

        @Volatile
        private var instance: CodeSyncManager? = null

        fun init(context: Context, repoUrl: String = ""): CodeSyncManager {
            return instance ?: synchronized(this) {
                instance ?: CodeSyncManager(context.applicationContext).also {
                    instance = it
                }
            }
        }

        fun getInstance(): CodeSyncManager =
            instance ?: throw IllegalStateException("CodeSyncManager not initialized. Call init() first.")
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState

    sealed class SyncState {
        object Idle : SyncState()
        object Checking : SyncState()
        object Downloading : SyncState()
        object Extracting : SyncState()
        data class Ready(val version: String) : SyncState()
        data class Error(val message: String) : SyncState()
    }

    /** 运行时目录: /data/data/<package>/files/app_python_runtime/ */
    fun getRuntimeDir(): File {
        return File(context.filesDir, RUNTIME_DIR).also { it.mkdirs() }
    }

    /** 版本文件本地路径 */
    private fun getLocalVersionFile(): File = File(getRuntimeDir(), VERSION_FILE)

    /** 获取本地版本号（commit SHA 前 7 位） */
    fun getLocalVersion(): String {
        val file = getLocalVersionFile()
        if (!file.exists()) return "0.0.0"
        return try {
            JSONObject(file.readText()).optString("version", "0.0.0")
        } catch (_: Exception) {
            "0.0.0"
        }
    }

    // ─── 同步流程 ─────────────────────────────────

    /**
     * 主入口：检查并同步最新代码
     *
     * @param force 是否强制同步（忽略版本比较）
     */
    suspend fun syncIfNeeded(force: Boolean = false): SyncResult = withContext(Dispatchers.IO) {
        try {
            _syncState.value = SyncState.Checking

            // 1. 获取远程版本信息
            val remoteSha = fetchRemoteCommitSha()
            val localVersion = if (force) "0.0.0" else getLocalVersion()

            Log.d(TAG, "Remote: $remoteSha, Local: $localVersion")

            if (!force && remoteSha == localVersion) {
                _syncState.value = SyncState.Ready(localVersion)
                return@withContext SyncResult.UpToDate(localVersion)
            }

            // 2. 下载仓库 ZIP
            _syncState.value = SyncState.Downloading
            val zipUrl = "https://api.github.com/repos/$GITHUB_REPO/zipball/$GITHUB_BRANCH"
            val zipFile = File(context.cacheDir, "repo_$GITHUB_BRANCH.zip")
            downloadFile(zipUrl, zipFile)

            // 3. 解压 agent/ 目录到运行时目录
            _syncState.value = SyncState.Extracting
            extractAgentDir(zipFile, getRuntimeDir())

            // 4. 保存版本号
            getLocalVersionFile().writeText("""{"version":"$remoteSha"}""")

            // 5. 清理缓存
            zipFile.delete()

            _syncState.value = SyncState.Ready(remoteSha)
            Log.i(TAG, "Synced to commit $remoteSha")
            SyncResult.Synced(remoteSha)

        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            _syncState.value = SyncState.Error(e.message ?: "Unknown")
            SyncResult.Failed(e.message ?: "同步失败")
        }
    }

    // ─── 远程版本查询 ─────────────────────────────

    private suspend fun fetchRemoteCommitSha(): String {
        return withContext(Dispatchers.IO) {
            try {
                val url = "https://api.github.com/repos/$GITHUB_REPO/commits/$GITHUB_BRANCH"
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Accept", "application/vnd.github.v3+json")
                    .build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "")
                    json.optString("sha", "0.0.0").take(7)
                } else {
                    Log.w(TAG, "Failed to fetch commit SHA: HTTP ${response.code}")
                    getLocalVersion()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch remote version", e)
                getLocalVersion()
            }
        }
    }

    // ─── 文件下载 ─────────────────────────────────

    private fun downloadFile(url: String, destFile: File) {
        val request = Request.Builder().url(url)
            .addHeader("Accept", "application/vnd.github.v3+json")
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("下载失败: HTTP ${response.code}")
        }

        response.body?.byteStream()?.use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw Exception("下载失败: 空响应体")
    }

    // ─── Zip 解压（提取 agent/ 目录）───────────────

    /**
     * 从 GitHub 仓库 ZIP 中提取 agent/ 目录到 destDir
     *
     * ZIP 结构: Dreamt0511-Pocket-Agent-<sha>/agent/...
     * 我们只需要 agent/ 及其子目录
     */
    private fun extractAgentDir(zipFile: File, destDir: File) {
        // 备份旧 skills 目录（防止用户自建技能被清除）
        val oldAgentDir = File(destDir, "agent")
        val skillsBackup = File(destDir.parentFile, "skills_backup")
        val oldSkillsDir = File(oldAgentDir, "skills")
        if (oldSkillsDir.exists()) {
            oldSkillsDir.copyRecursively(skillsBackup, overwrite = true)
            Log.i(TAG, "Backed up skills to ${skillsBackup.absolutePath}")
        }

        // 清空旧 agent 目录
        if (oldAgentDir.exists()) {
            oldAgentDir.deleteRecursively()
        }

        var foundAgent = false

        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name

                // GitHub ZIP 格式: <repo>-<sha>/agent/...
                // 找到 "agent/" 在路径中的位置
                val agentIndex = name.indexOf("/agent/")
                val isAgentEntry = agentIndex >= 0
                val isAgentDir = name.endsWith("/agent") && name.count { it == '/' } == 1

                if (isAgentEntry || isAgentDir) {
                    foundAgent = true
                    val relativePath = if (isAgentEntry) {
                        name.substring(agentIndex + 1) // "agent/..."
                    } else {
                        "agent"
                    }
                    val targetFile = File(destDir, relativePath)

                    if (entry.isDirectory) {
                        targetFile.mkdirs()
                    } else {
                        targetFile.parentFile?.mkdirs()
                        FileOutputStream(targetFile).use { fos ->
                            zis.copyTo(fos)
                        }
                    }
                }

                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        // 恢复备份的技能目录
        if (skillsBackup.exists()) {
            val newSkillsDir = File(File(destDir, "agent"), "skills")
            skillsBackup.copyRecursively(newSkillsDir, overwrite = true)
            skillsBackup.deleteRecursively()
            Log.i(TAG, "Restored skills backup to ${newSkillsDir.absolutePath}")
        }

        if (!foundAgent) {
            Log.w(TAG, "No agent/ directory found in ZIP")
        }
    }

    /** 获取入口脚本路径 */
    fun getEntryPoint(): File {
        return File(getRuntimeDir(), "stable_entry.py")
    }

    /** 检查入口脚本是否存在 */
    fun isCodeReady(): Boolean {
        return getEntryPoint().exists()
    }

    /** 确保种子代码存在 — 无网络时使用内置代码 */
    fun ensureSeedCode(): Boolean {
        val entry = getEntryPoint()
        if (entry.exists()) return true
        val runtimeDir = getRuntimeDir()
        if (!runtimeDir.exists()) runtimeDir.mkdirs()
        try {
            entry.writeText("# Pocket Agent Seed Entry\nprint('{\"type\": \"ready\"}')\n")
            Log.i(TAG, "Seed code created at ${entry.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create seed code", e)
            return false
        }
    }
}

// ─── 同步结果 ────────────────────────────────────

sealed class SyncResult {
    data class UpToDate(val version: String) : SyncResult()
    data class Synced(val version: String) : SyncResult()
    data class Failed(val error: String) : SyncResult()
}
