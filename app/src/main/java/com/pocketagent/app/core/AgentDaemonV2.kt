package com.pocketagent.app.core

import android.content.Context
import android.util.Log
import com.pocketagent.app.overlay.StreamBridge
import com.pocketagent.app.termux.TermuxBridge
import com.pocketagent.app.update.CodeSyncManager
import com.pocketagent.app.update.PythonRuntime
import com.pocketagent.app.update.SyncResult
import com.pocketagent.app.update.TaskResult
import com.pocketagent.app.update.ActionResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 新一轮 AgentDaemon — 集成动态代码加载架构
 *
 * 与旧版区别：
 *  - 旧版: 通过 stdin/stdout 管道与嵌入式 Python 进程通信
 *  - 新版: 通过 Chaquopy 在 APK 内直接运行主仓库 Python 代码
 *  - 启动时自动检查代码更新并同步
 *
 * 生命周期：
 *   init(context) → checkUpdates() → initRuntime() → execute(command) → cancel()
 */
class AgentDaemonV2(private val context: Context) {

    companion object {
        private const val TAG = "AgentDaemonV2"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val termuxBridge = TermuxBridge()
    private val pythonRuntime = PythonRuntime(context, termuxBridge)
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

    // ─── 启动流程 ─────────────────────────────────

    /**
     * 完整启动流程（App 启动时调用一次）
     *
     *  1. 检查主仓库代码更新 → 同步
     *  2. 初始化 Python 运行时
     *  3. 进入就绪状态
     */
    suspend fun bootstrap(): Boolean {
        Log.i(TAG, "=== Bootstrap start ===")

        // 第一步：代码同步
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

        // 第二步：初始化运行时
        _status.value = DaemonStatus.Initializing
        StreamBridge.status("初始化 Python 环境...")
        StreamBridge.out("[info] 启动 Python 运行时...\n")

        val initialized = pythonRuntime.initialize()
        if (!initialized) {
            _status.value = DaemonStatus.Error("Python 运行时初始化失败，请安装 Termux 并在其中安装 Python")
            StreamBridge.out("[error] Python 运行时不可用\n")
            return false
        }

        // 第三步：就绪
        _status.value = DaemonStatus.Ready
        StreamBridge.status("就绪")
        StreamBridge.out("$ Pocket Agent 就绪 — 等待指令\n")
        Log.i(TAG, "=== Bootstrap complete ===")
        return true
    }

    // ─── 执行用户指令 ─────────────────────────────

    /**
     * 执行用户的自然语言指令
     *
     * 集成流式输出：
     *  每步输出 → StreamBridge.out() → 悬浮窗 + 终端
     */
    suspend fun execute(command: String): TaskResult {
        val s = _status.value

        // Python 运行时模式
        if (s !is DaemonStatus.Ready) {
            return TaskResult.Failure("Agent 未就绪，请等待初始化完成")
        }

        _status.value = DaemonStatus.Executing
        StreamBridge.status("执行中")
        StreamBridge.out("$ $command\n")

        // 注册输出回调
        pythonRuntime.setOnOutput { line -> StreamBridge.out(line) }
        pythonRuntime.setOnStatus { status -> StreamBridge.status(status) }

        // 注册 Android 能力回调（无障碍操作信号）
        pythonRuntime.setOnAction { request ->
            StreamBridge.task("执行: ${request.type} → ${request.target}")

            // 操控手机前检查 NeuralBridge 就绪状态
            if (request.type in listOf("click", "swipe", "input", "launch_app")) {
                val ready = NeuralBridgeHelper.checkAndAlert(
                    context,
                    "Agent 尝试执行: ${request.type}(${request.target})"
                )
                if (!ready) {
                    StreamBridge.info("NeuralBridge 未就绪，请查看弹窗提示")
                }
                StreamBridge.signalOperation(true)
            }

            // 实际执行由 AccessibilityService 处理
            // 这里返回模拟结果，真实操作在 AccessibilityActionHandler 中
            StreamBridge.signalOperation(false)
            ActionResult.Success()
        }

        // 执行
        val result = pythonRuntime.execute(command)

        _status.value = DaemonStatus.Ready
        StreamBridge.status("空闲")

        when (result) {
            is TaskResult.Success -> StreamBridge.done(result.message)
            is TaskResult.Failure -> StreamBridge.error(result.error)
            is TaskResult.Cancelled -> StreamBridge.info("任务已中断")
        }

        return result
    }

    /**
     * 中断当前任务
     */
    fun cancel() {
        pythonRuntime.cancel()
        StreamBridge.info("中断信号已发送")
    }

    /**
     * 强制同步代码（用户手动触发）
     */
    suspend fun forceSync(): SyncResult {
        _status.value = DaemonStatus.Syncing
        StreamBridge.status("强制同步...")
        return codeManager.syncIfNeeded(force = true).also { result ->
            when (result) {
                is SyncResult.Synced -> {
                    StreamBridge.done("代码已更新到 v${result.version}")
                    // 重新初始化
                    scope.launch {
                        val ok = pythonRuntime.initialize()
                        _status.value = if (ok) DaemonStatus.Ready
                        else DaemonStatus.Error("Python 未安装")
                    }
                }
                else -> {
                    _status.value = DaemonStatus.Ready
                }
            }
        }
    }

    fun destroy() {
        scope.cancel()
        pythonRuntime.destroy()
    }
}