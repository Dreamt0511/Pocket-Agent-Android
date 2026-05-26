package com.pocketagent.app.service

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 会话管理器 — 将会话元数据与任务持久化到 JSON 文件。
 *
 * 存储结构：
 *   filesDir/sessions/index.json         — 所有会话索引
 *   filesDir/sessions/<sessionId>/tasks/ — 该会话下的任务 JSON
 */
class SessionManager(private val context: Context) {

    private val gson = Gson()
    private val sessionsDir: File get() = File(context.filesDir, "sessions")
    private val indexFile: File get() = File(sessionsDir, "index.json")

    // ─── 数据模型 ─────────────────────────────────

    data class Session(
        val id: String,
        val title: String,
        val createdAt: Long,
        val updatedAt: Long,
        val taskCount: Int = 0
    )

    data class StoredTask(
        val id: String,
        val sessionId: String,
        val prompt: String,
        val status: String,
        val output: String,
        val createdAt: Long,
        val completedAt: Long?
    )

    // ─── 会话 CRUD ────────────────────────────────

    suspend fun createSession(title: String): Session = withContext(Dispatchers.IO) {
        val sessions = loadSessions().toMutableList()
        val session = Session(
            id = UUID.randomUUID().toString(),
            title = title,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        sessions.add(0, session)
        saveSessions(sessions)
        session
    }

    suspend fun getSessions(): List<Session> = withContext(Dispatchers.IO) {
        loadSessions()
    }

    suspend fun getSession(sessionId: String): Session? = withContext(Dispatchers.IO) {
        loadSessions().find { it.id == sessionId }
    }

    suspend fun deleteSession(sessionId: String) = withContext(Dispatchers.IO) {
        val sessions = loadSessions().toMutableList()
        sessions.removeAll { it.id == sessionId }
        saveSessions(sessions)
        // 删除会话任务目录
        val taskDir = File(sessionsDir, "$sessionId/tasks")
        taskDir.deleteRecursively()
    }

    suspend fun updateSession(sessionId: String, title: String? = null) = withContext(Dispatchers.IO) {
        val sessions = loadSessions().toMutableList()
        val idx = sessions.indexOfFirst { it.id == sessionId }
        if (idx >= 0) {
            val old = sessions[idx]
            sessions[idx] = old.copy(
                title = title ?: old.title,
                updatedAt = System.currentTimeMillis()
            )
            saveSessions(sessions)
        }
    }

    suspend fun clearAllSessions() = withContext(Dispatchers.IO) {
        sessionsDir.deleteRecursively()
    }

    // ─── 任务管理 ─────────────────────────────────

    suspend fun addTask(task: StoredTask) = withContext(Dispatchers.IO) {
        val taskFile = File(sessionsDir, "${task.sessionId}/tasks/${task.id}.json")
        taskFile.parentFile?.mkdirs()
        taskFile.writeText(gson.toJson(task))

        // 更新会话的 taskCount 和 updatedAt
        val sessions = loadSessions().toMutableList()
        val idx = sessions.indexOfFirst { it.id == task.sessionId }
        if (idx >= 0) {
            val old = sessions[idx]
            val taskCount = File(sessionsDir, "${task.sessionId}/tasks").listFiles()
                ?.count { it.name.endsWith(".json") } ?: 0
            sessions[idx] = old.copy(
                taskCount = taskCount,
                updatedAt = System.currentTimeMillis()
            )
            saveSessions(sessions)
        }
    }

    suspend fun getSessionTasks(sessionId: String): List<StoredTask> = withContext(Dispatchers.IO) {
        val taskDir = File(sessionsDir, "$sessionId/tasks")
        if (!taskDir.isDirectory) return@withContext emptyList()
        taskDir.listFiles { f -> f.name.endsWith(".json") }
            ?.map { f -> gson.fromJson(f.readText(), StoredTask::class.java) }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }

    // ─── 内部 ─────────────────────────────────────

    private fun loadSessions(): List<Session> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<Session>>() {}.type
            gson.fromJson(indexFile.readText(), type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveSessions(sessions: List<Session>) {
        sessionsDir.mkdirs()
        indexFile.writeText(gson.toJson(sessions))
    }
}
