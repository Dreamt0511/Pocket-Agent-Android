package com.pocketagent.app.core

import android.content.Context
import android.util.Log
import com.pocketagent.app.overlay.OverlayManager
import com.pocketagent.app.overlay.StreamBridge
import com.pocketagent.app.update.CodeSyncManager
import com.pocketagent.app.service.TaskQueueManager
import com.pocketagent.app.update.TaskResult
import com.pocketagent.app.update.UpdateChecker
import com.pocketagent.app.core.SkillManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow

/**
 * App 启动协调器 — 统一的启动入口
 *
 * 在 Application.onCreate() 中调用一次:
 *   AppBootstrapper.init(this, "https://github.com/xxx/pocket-agent")
 *   AppBootstrapper.start()
 *
 * 启动顺序：
 *  1. OverlayManager.init       — 悬浮窗管理器
 *  2. CodeSyncManager.init       — 代码同步引擎
 *  3. UpdateChecker.init         — 统一更新检查
 *  4. AgentDaemonV2.bootstrap()  — 下载代码 + 初始化 Python 运行时
 *  5. AgentDaemonV2.execute()    — 等待用户指令
 */
object AppBootstrapper {

    private const val TAG = "AppBootstrapper"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var daemon: AgentDaemon
    private var repoUrl: String = ""

    /** 任务队列管理器（供历史页面使用） */
    val taskQueueManager: TaskQueueManager = TaskQueueManager()

    /** 暴露 daemon 状态供 UI 层收集 */
    val daemonStatus: StateFlow<AgentDaemon.DaemonStatus>
        get() = daemon.status

    /**
     * 初始化所有子系统（Application.onCreate 中调用）
     */
    fun init(context: Context, repoUrl: String = "https://github.com/pocketagent/pocket-agent") {
        this.repoUrl = repoUrl
        appContext = context.applicationContext

        // 1. 悬浮窗管理器
        OverlayManager.init(context)

        // 2. 代码同步引擎
        CodeSyncManager.init(context, repoUrl)

        // 3. 更新检查器
        UpdateChecker.init(context, repoUrl)

        // 4. Agent 守护进程（共享 taskQueueManager）
        daemon = AgentDaemon(context, taskQueueManager)

        // 5. 技能管理器（数据目录就绪后初始化）
        SkillManager.init(context)

        Log.i(TAG, "All subsystems initialized")
    }

    /**
     * 异步启动流程（MainActivity.onCreate 中调用）
     *
     * 用户看到的启动流程：
     *  闪屏 → 检查更新 → 同步代码 → 初始化 Python → 进入主界面
     */
    fun start(): Job {
        return scope.launch {
            StreamBridge.status("正在启动...")
            StreamBridge.out("[info] Pocket Agent 启动中\n")

            if (!TermuxLauncher.isTermuxInstalled(getContext())) {
                StreamBridge.error("请先安装 Termux、Termux:API、Termux:Boot")
                return@launch
            }

            // 代码同步 + Python 初始化
            val success = daemon.bootstrap()

            if (success) {
                // 代码同步可能改变了 skills 目录，重新扫描
                SkillManager.rescan()
                StreamBridge.status("就绪 — 随时可以开始")
            } else {
                StreamBridge.error("启动失败，请检查网络连接后重试")
            }
        }
    }

    /**
     * 执行用户指令
     */
    suspend fun executeCommand(command: String, sessionId: String = ""): TaskResult {
        return daemon.execute(command, sessionId)
    }

    /**
     * 强制同步代码
     */
    suspend fun forceSync() {
        daemon.forceSync()
    }

    /**
     * 手动检查更新（代码 + APK）
     */
    suspend fun checkAllUpdates() {
        StreamBridge.info("检查更新中...")
        val event = UpdateChecker.checkAll()

        when (event) {
            is UpdateChecker.UpdateEvent.CodeUpdated -> {
                StreamBridge.done("代码已更新到 v${event.version}")
                // 重新初始化 Python 运行时
                daemon = AgentDaemon(getContext(), taskQueueManager)
                daemon.bootstrap()
            }
            is UpdateChecker.UpdateEvent.AppUpdateAvailable -> {
                StreamBridge.info("发现新版本 APK: v${event.newVersion}")
                // 由 UI 层弹窗询问用户是否下载
            }
            is UpdateChecker.UpdateEvent.UpToDate -> {
                StreamBridge.info("已是最新版本")
            }
            is UpdateChecker.UpdateEvent.Error -> {
                StreamBridge.error(event.message)
            }
            else -> {}
        }
    }

    /**
     * 获取当前版本信息
     */
    fun getVersionInfo(): VersionInfo {
        val codeManager = CodeSyncManager.getInstance()
        return VersionInfo(
            codeVersion = codeManager.getLocalVersion(),
            daemonStatus = daemon.status.value::class.simpleName ?: "Unknown"
        )
    }

    data class VersionInfo(
        val codeVersion: String,
        val daemonStatus: String
    )

    private var appContext: Context? = null

    private fun getContext(): Context {
        return appContext ?: throw IllegalStateException("AppBootstrapper not initialized")
    }

    /**
     * PocketAgentApp.onCreate() 中调用的完整示例:
     *
     * ```kotlin
     * class PocketAgentApp : Application() {
     *     override fun onCreate() {
     *         super.onCreate()
     *         AppBootstrapper.init(
     *             this,
     *             "https://github.com/your-org/pocket-agent"
     *         )
     *     }
     * }
     * ```
     */
}