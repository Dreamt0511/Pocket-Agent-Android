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
//        private const val RUNTIME_DIR = "app_python_runtime"
        private const val TARGET_DIR = "/data/data/com.termux/files/home/Pocket-Agent"
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

    /** 运行时目录: Termux home/Pocket-Agent/ */
    fun getRuntimeDir(): File {
        return File(TARGET_DIR).also { it.mkdirs() }
    }

    /** 版本文件本地路径 */
    private fun getLocalVersionFile(): File = File(getRuntimeDir(), VERSION_FILE)

    /** 获取本地版本号（commit SHA 前 7 位） */
    fun getLocalVersion(): String {
        val file = getLocalVersionFile()
        if (!file.exists()) return "未安装"
        return try {
            val v = JSONObject(file.readText()).optString("version", "")
            if (v.isBlank()) "未安装" else v.take(7)
        } catch (_: Exception) {
            "未安装"
        }
    }

    /** 获取最近一次更新时间戳（毫秒），0 表示未更新过 */
    fun getLastUpdateTime(): Long {
        val file = getLocalVersionFile()
        if (!file.exists()) return 0
        return try {
            JSONObject(file.readText()).optLong("timestamp", 0)
        } catch (_: Exception) { 0 }
    }

    /** 获取版本历史列表（最新在前） */
    fun getVersionHistory(): List<VersionEntry> {
        val file = getLocalVersionFile()
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONObject(file.readText()).optJSONArray("history") ?: return emptyList()
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                VersionEntry(
                    sha = obj.optString("version", "").take(7),
                    timestamp = obj.optLong("timestamp", 0)
                )
            }.reversed()
        } catch (_: Exception) { emptyList() }
    }

    data class VersionEntry(val sha: String, val timestamp: Long)

    /** 回退到指定版本（从 history 中找到并重新解压） */
    suspend fun rollbackTo(sha: String): SyncResult = withContext(Dispatchers.IO) {
        try {
            _syncState.value = SyncState.Downloading
            // 下载指定版本的 ZIP
            val zipUrl = "https://api.github.com/repos/$GITHUB_REPO/zipball/$sha"
            val zipFile = File(context.cacheDir, "repo_$sha.zip")
            downloadFile(zipUrl, zipFile)

            _syncState.value = SyncState.Extracting
            extractAgentDir(zipFile, getRuntimeDir())

            // 更新版本文件（保留历史）
            saveVersion(sha)
            zipFile.delete()

            _syncState.value = SyncState.Ready(sha)
            SyncResult.Synced(sha)
        } catch (e: Exception) {
            _syncState.value = SyncState.Error(e.message ?: "回退失败")
            SyncResult.Failed(e.message ?: "回退失败")
        }
    }

    /** 保存版本信息（维护历史记录，最多保留 10 条） */
    private fun saveVersion(sha: String) {
        val file = getLocalVersionFile()
        val now = System.currentTimeMillis()
        try {
            val existing = if (file.exists()) JSONObject(file.readText()) else JSONObject()
            // 将当前版本加入历史
            val history = existing.optJSONArray("history") ?: org.json.JSONArray()
            val currentSha = existing.optString("version", "")
            if (currentSha.isNotBlank()) {
                history.put(JSONObject().apply {
                    put("version", currentSha)
                    put("timestamp", existing.optLong("timestamp", now))
                })
            }
            // 只保留最近 10 条
            while (history.length() > 10) history.remove(0)

            val obj = JSONObject().apply {
                put("version", sha)
                put("timestamp", now)
                put("history", history)
            }
            file.writeText(obj.toString(2))
        } catch (_: Exception) {
            file.writeText("""{"version":"$sha","timestamp":$now,"history":[]}""")
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

            // 4. 保存版本号（含历史记录）
            saveVersion(remoteSha)

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

    // ─── Zip 解压（提取仓库根目录）───────────────

    /**
     * 从 GitHub 仓库 ZIP 中提取全部代码到 destDir
     *
     * ZIP 结构: Dreamt0511-Pocket-Agent-<sha>/
     *   ├── main.py
     *   ├── pyproject.toml
     *   ├── requirements.txt
     *   ├── .env.example
     *   ├── agent/
     *   ├── docs/            ← 跳过
     *   ├── .github/         ← 跳过
     *   └── ...
     *
     * 跳过 docs/、.github/、.gitattributes、.gitignore、README.md。
     * 保留 .env（用户配置），同步后恢复 skills/ 和 config.py 自定义值。
     */
    private fun extractAgentDir(zipFile: File, destDir: File) {
        val oldAgentDir = File(destDir, "agent")

        // ── 备份 ─────────────────────────────────

        // 备份 skills（防止用户自建技能被清除）
        val skillsBackup = File(destDir.parentFile, "skills_backup")
        val oldSkillsDir = File(oldAgentDir, "skills")
        if (oldSkillsDir.exists()) {
            oldSkillsDir.copyRecursively(skillsBackup, overwrite = true)
            Log.i(TAG, "Backed up skills to ${skillsBackup.absolutePath}")
        }

        // 备份 config.py（防止用户自定义配置被清除）
        val configBackup = File(destDir.parentFile, "config_py_backup")
        val oldConfigPy = File(oldAgentDir, "config.py")
        if (oldConfigPy.exists()) {
            oldConfigPy.copyTo(configBackup, overwrite = true)
            Log.i(TAG, "Backed up config.py to ${configBackup.absolutePath}")
        }

        // ── 清理旧文件（保留 .env） ──────────────
        destDir.listFiles()?.forEach { file ->
            if (file.name != ".env") {
                file.deleteRecursively()
            }
        }

        // ── 需要跳过的文件/目录 ─────────────────
        val skipPrefixes = setOf(
            "docs/", ".github/", ".gitattributes", ".gitignore",
            "README.md", ".git/",
        )

        // ── 解压 ─────────────────────────────────
        var extractedCount = 0

        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name

                // GitHub ZIP 格式: <repo>-<sha>/<relative-path>
                val slashIndex = name.indexOf('/')
                val relativePath = if (slashIndex >= 0) {
                    name.substring(slashIndex + 1) // 去掉顶层目录
                } else {
                    name // 顶层目录自身（跳过）
                }

                // 跳过非仓库内容
                val skip = relativePath.isEmpty() ||
                        skipPrefixes.any { relativePath == it || relativePath.startsWith(it) }

                if (!skip) {
                    val targetFile = File(destDir, relativePath)
                    if (entry.isDirectory) {
                        targetFile.mkdirs()
                    } else {
                        targetFile.parentFile?.mkdirs()
                        FileOutputStream(targetFile).use { fos ->
                            zis.copyTo(fos)
                        }
                        extractedCount++
                    }
                }

                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        // ── 恢复 ─────────────────────────────────

        // 恢复 skills
        if (skillsBackup.exists()) {
            val newSkillsDir = File(File(destDir, "agent"), "skills")
            skillsBackup.copyRecursively(newSkillsDir, overwrite = true)
            skillsBackup.deleteRecursively()
            Log.i(TAG, "Restored skills backup to ${newSkillsDir.absolutePath}")
        }

        // 恢复 config.py 用户自定义值
        if (configBackup.exists()) {
            val newConfigPy = File(File(destDir, "agent"), "config.py")
            if (newConfigPy.exists()) {
                val oldValues = readConfigPyValues(configBackup)
                var content = newConfigPy.readText()
                var changed = false
                for ((key, value) in oldValues) {
                    val pattern = """^($key)\s*=\s*\d+""".toRegex(RegexOption.MULTILINE)
                    if (pattern.containsMatchIn(content)) {
                        content = content.replace(pattern, "$1 = $value")
                        changed = true
                    }
                }
                if (changed) {
                    newConfigPy.writeText(content)
                    Log.i(TAG, "Restored user config.py values from backup")
                }
            }
            configBackup.delete()
        }

        Log.i(TAG, "Extracted $extractedCount files from repo (skipped: docs, .github, etc.)")
    }

    /**
     * 从备份的 config.py 中读取用户自定义的数值键值对
     */
    private fun readConfigPyValues(file: File): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val pattern = """^(MAX_ITERATIONS|RECURSION_LIMIT|MAX_CONTEXT_TOKENS)\s*=\s*(\d+)""".toRegex(RegexOption.MULTILINE)
        for (match in pattern.findAll(file.readText())) {
            result[match.groupValues[1]] = match.groupValues[2]
        }
        return result
    }

    /** 获取入口脚本路径 */
    fun getEntryPoint(): File {
        return File(getRuntimeDir(), "app.py")
    }

    /** 检查入口脚本是否存在 */
    fun isCodeReady(): Boolean {
        return getEntryPoint().exists()
    }

    /** 确保种子代码存在 — 不再需要 seed code，直接检查入口文件 */
    fun ensureSeedCode(): Boolean {
        return getEntryPoint().exists()
    }
}

// ─── 同步结果 ────────────────────────────────────

sealed class SyncResult {
    data class UpToDate(val version: String) : SyncResult()
    data class Synced(val version: String) : SyncResult()
    data class Failed(val error: String) : SyncResult()
}
