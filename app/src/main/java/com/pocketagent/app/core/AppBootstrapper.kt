package com.pocketagent.app.core

import android.content.Context
import android.util.Log
import com.pocketagent.app.overlay.OverlayManager
import com.pocketagent.app.overlay.StreamBridge
import com.pocketagent.app.service.TaskQueueManager
import com.pocketagent.app.update.TaskResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow

/**
 * App 启动协调器
 *
 * 启动顺序：
 *  1. OverlayManager.init
 *  2. AgentDaemon.init
 *  3. SkillManager.init
 *  4. AgentDaemon.bootstrap() — 连接 Termux 服务
 */
object AppBootstrapper {

    private const val TAG = "AppBootstrapper"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var daemon: AgentDaemon

    val taskQueueManager: TaskQueueManager = TaskQueueManager()

    val daemonStatus: StateFlow<AgentDaemon.DaemonStatus>
        get() = daemon.status

    fun init(context: Context) {
        appContext = context.applicationContext

        OverlayManager.init(context)
        daemon = AgentDaemon(context, taskQueueManager)
        SkillManager.init(context)

        Log.i(TAG, "All subsystems initialized")
    }

    fun start(): Job {
        return scope.launch {
            StreamBridge.status("正在启动...")
            StreamBridge.out("[info] Pocket Agent 启动中\n")

            if (!TermuxLauncher.isTermuxInstalled(getContext())) {
                StreamBridge.error("请先安装 Termux、Termux:API、Termux:Boot")
                return@launch
            }

            if (daemon.bootstrap()) {
                SkillManager.rescan()
                StreamBridge.status("就绪 — 随时可以开始")
            } else {
                StreamBridge.error("启动失败，请检查 Termux 配置")
            }
        }
    }

    suspend fun executeCommand(command: String, sessionId: String = ""): TaskResult {
        return daemon.execute(command, sessionId)
    }

    suspend fun forceSync() {
        daemon.forceSync()
    }

    fun getVersionInfo(): VersionInfo {
        return VersionInfo(
            codeVersion = "0.0.0",
            daemonStatus = daemon.status.value::class.simpleName ?: "Unknown"
        )
    }

    data class VersionInfo(
        val codeVersion: String,
        val daemonStatus: String
    )

    suspend fun checkAllUpdates() {
        StreamBridge.info("检查更新中...")
        daemon.forceSync()
    }

    private var appContext: Context? = null

    private fun getContext(): Context {
        return appContext ?: throw IllegalStateException("AppBootstrapper not initialized")
    }
}
