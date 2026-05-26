package com.pocketagent.app.core

import android.content.Context
import android.util.Log
import com.pocketagent.app.overlay.StreamBridge
import com.pocketagent.app.update.CodeSyncManager
import com.pocketagent.app.service.TaskQueueManager
import com.pocketagent.app.update.SyncResult
import com.pocketagent.app.update.TaskResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AgentDaemon(
    private val context: Context,
    private val taskQueueManager: TaskQueueManager = TaskQueueManager()
) {
    companion object {
        private const val TAG = "AgentDaemon"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val codeManager: CodeSyncManager
        get() = CodeSyncManager.getInstance()

    private val _status = MutableStateFlow<DaemonStatus>(DaemonStatus.Idle)
    val status: StateFlow<DaemonStatus> = _status

    sealed class DaemonStatus {
        object Idle : DaemonStatus()
        object Syncing : DaemonStatus()
        object Initializing : DaemonStatus()
        object Ready : DaemonStatus()
        object Executing : DaemonStatus()
        data class Error(val message: String) : DaemonStatus()
    }

    suspend fun bootstrap(): Boolean {
        Log.i(TAG, "=== Bootstrap start ===")

        _status.value = DaemonStatus.Syncing
        StreamBridge.status("检查代码更新...")
        StreamBridge.out("[info] 正在连接主仓库...\n")

        val syncResult = codeManager.syncIfNeeded()
        when (syncResult) {
            is SyncResult.Synced -> {
                StreamBridge.out("[done] 代码已更新到 v${syncResult.version}\n")
                StreamBridge.status("代码同步完成")
            }
            is SyncResult.UpToDate -> {
                StreamBridge.out("[info] 代码已是最新 (v${syncResult.version})\n")
                StreamBridge.status("代码已就绪")
            }
            is SyncResult.Failed -> {
                StreamBridge.error("代码同步失败: ${syncResult.error}")
                _status.value = DaemonStatus.Error(syncResult.error)
                return false
            }
        }

        _status.value = DaemonStatus.Initializing
        StreamBridge.status("连接 Termux 服务...")
        StreamBridge.out("[info] 检查 Termux FastAPI 服务...\n")

        when (val health = TermuxServiceClient.healthCheck()) {
            is TermuxServiceClient.HealthResult.Ok -> {
                StreamBridge.out("[done] Termux 服务已连接\n")
            }
            is TermuxServiceClient.HealthResult.Error -> {
                StreamBridge.out("[info] 正在启动 Termux 服务...\n")
                TermuxLauncher.launchFastAPI(context)
                delay(3000)
                when (val retry = TermuxServiceClient.healthCheck()) {
                    is TermuxServiceClient.HealthResult.Ok -> {
                        StreamBridge.out("[done] Termux 服务已启动\n")
                    }
                    is TermuxServiceClient.HealthResult.Error -> {
                        StreamBridge.error("Termux 服务启动失败，请确认 Termux 已安装并配置好环境")
                        _status.value = DaemonStatus.Error("Termux 服务不可用")
                        return false
                    }
                }
            }
        }

        _status.value = DaemonStatus.Ready
        StreamBridge.status("就绪")
        StreamBridge.out("$ Pocket Agent 就绪 — 等待指令\n")
        Log.i(TAG, "=== Bootstrap complete ===")
        return true
    }

    suspend fun execute(command: String, sessionId: String = ""): TaskResult {
        val s = _status.value
        if (s !is DaemonStatus.Ready) {
            return TaskResult.Failure("Agent 未就绪，请等待初始化完成")
        }

        val task = taskQueueManager.enqueue(command, sessionId)
        _status.value = DaemonStatus.Executing
        StreamBridge.status("执行中")
        StreamBridge.out("$ $command\n")

        val output = StringBuilder()
        try {
            TermuxServiceClient.chatStream(command).collect { data ->
                StreamBridge.out(data)
                output.append(data)
                task.output.value = output.toString()
            }
            taskQueueManager.onTaskComplete(task, true, output.toString())
            _status.value = DaemonStatus.Ready
            StreamBridge.status("空闲")
            StreamBridge.done(output.toString())
            return TaskResult.Success(output.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Execute failed", e)
            taskQueueManager.onTaskComplete(task, false, "错误: ${e.message}")
            _status.value = DaemonStatus.Ready
            StreamBridge.status("空闲")
            StreamBridge.error(e.message ?: "Unknown error")
            return TaskResult.Failure(e.message ?: "执行失败")
        }
    }

    fun cancel() {
        StreamBridge.info("中断信号已发送")
    }

    suspend fun forceSync(): SyncResult {
        _status.value = DaemonStatus.Syncing
        StreamBridge.status("强制同步...")
        return codeManager.syncIfNeeded(force = true).also { result ->
            when (result) {
                is SyncResult.Synced -> {
                    StreamBridge.done("代码已更新到 v${result.version}")
                    scope.launch { bootstrap() }
                }
                else -> _status.value = DaemonStatus.Ready
            }
        }
    }

    fun destroy() {
        scope.cancel()
    }
}
