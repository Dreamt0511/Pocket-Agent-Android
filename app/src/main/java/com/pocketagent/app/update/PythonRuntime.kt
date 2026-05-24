package com.pocketagent.app.update

import android.content.Context
import android.util.Log
import com.pocketagent.app.termux.TermuxBridge
import com.pocketagent.app.termux.TermuxBootstrap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

/**
 * Python 运行时管理器 — 通过 Termux 的 Python 执行动态加载的主仓库代码
 *
 * 架构：
 *   Android (Kotlin)  ←→  Termux (Python + stable_entry.py)
 *          │                         │
 *          │  execute("帮我去...")     │ 通过 stdin 下发用户指令
 *          │  stable_entry.py         │ 通过 stdout 输出 JSONL
 *          │  onOutput ←───────────  │ 流式输出回调
 *          │  onStatus ←───────────  │ 状态更新回调
 *
 * 依赖：
 *   - Termux app 已安装（或将来内嵌）
 *   - Python 3 已安装 (pkg install python)
 *   - pip 依赖已安装
 */
class PythonRuntime(
    private val context: Context,
    private val termuxBridge: TermuxBridge
) {

    companion object {
        private const val TAG = "PythonRuntime"
        private const val SEED_ASSET_DIR = "agent-seed"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _state = MutableStateFlow<RuntimeState>(RuntimeState.Idle)
    val state: StateFlow<RuntimeState> = _state

    sealed class RuntimeState {
        object Idle : RuntimeState()
        object Initializing : RuntimeState()
        object Ready : RuntimeState()
        object Executing : RuntimeState()
        data class Error(val message: String) : RuntimeState()
    }

    private var onOutput: ((String) -> Unit)? = null
    private var onStatus: ((String) -> Unit)? = null
    private var onAction: ((ActionRequest) -> ActionResult)? = null
    private var onComplete: ((TaskResult) -> Unit)? = null

    @Volatile
    private var cancelled = false

    // ─── 公开接口 ─────────────────────────────────

    fun setOnOutput(callback: (String) -> Unit) { onOutput = callback }
    fun setOnStatus(callback: (String) -> Unit) { onStatus = callback }
    fun setOnAction(callback: (ActionRequest) -> ActionResult) { onAction = callback }
    fun setOnComplete(callback: (TaskResult) -> Unit) { onComplete = callback }

    /**
     * 初始化 Python 运行时
     *
     * 流程：
     *  1. 检查 Termux 环境
     *  2. 从 APK assets 解压种子代码到运行时目录
     *  3. 验证 Python 可用
     *  4. 验证 Agent 代码就绪 (--mode=ready)
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        _state.value = RuntimeState.Initializing
        onStatus?.invoke("初始化 Python 环境...")

        try {
            // 1. 检查 Termux
            if (!TermuxBootstrap.isReady) {
                val msg = "Termux 未安装或不可用，请先安装 Termux"
                _state.value = RuntimeState.Error(msg)
                onStatus?.invoke(msg)
                return@withContext false
            }

            // 2. 解压种子代码
            extractSeedCode()
            val runtimeDir = getRuntimeDir()
            Log.i(TAG, "Seed code ready at ${runtimeDir.absolutePath}")

            // 3. 验证 Python
            val pythonCheck = termuxBridge.execute(
                "${TermuxBootstrap.termuxUsr}/bin/python3 --version"
            )
            if (!pythonCheck.success) {
                val msg = "Python 3 不可用 (exit=${pythonCheck.exitCode}): ${pythonCheck.output}"
                _state.value = RuntimeState.Error(msg)
                onStatus?.invoke("Python 未安装")
                return@withContext false
            }
            onStatus?.invoke("Python 就绪")

            // 4. 验证 Agent 代码
            val readyCmd = "cd ${runtimeDir.absolutePath} && " +
                    "${TermuxBootstrap.termuxUsr}/bin/python3 stable_entry.py --mode=ready"
            val readyResult = termuxBridge.execute(readyCmd)
            if (!readyResult.success) {
                val msg = "Agent 验证失败: ${readyResult.output}"
                _state.value = RuntimeState.Error(msg)
                onStatus?.invoke("Agent 代码异常")
                return@withContext false
            }

            _state.value = RuntimeState.Ready
            onStatus?.invoke("就绪")
            Log.i(TAG, "Python runtime initialized successfully")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Initialize failed", e)
            _state.value = RuntimeState.Error(e.message ?: "初始化失败")
            onStatus?.invoke("初始化失败")
            false
        }
    }

    /**
     * 执行用户指令
     *
     * 通过 stable_entry.py 的 stdin/stdout JSONL 协议通信：
     *  输入  → stdin:  用户指令文本
     *  输出  → stdout: {"type":"step","status":"...","message":"..."}
     *
     * 协议类型：
     *  - step (status=planning|executing|done|error|info|warning)
     *  - result (content=最终结果)
     *  - ready (agent 状态)
     */
    suspend fun execute(command: String): TaskResult = withContext(Dispatchers.IO) {
        _state.value = RuntimeState.Executing
        cancelled = false
        onStatus?.invoke("执行中...")

        try {
            val runtimeDir = getRuntimeDir()
            val pythonBin = "${TermuxBootstrap.termuxUsr}/bin/python3"
            val shellCmd = "cd ${runtimeDir.absolutePath} && $pythonBin stable_entry.py --mode=task"

            val result = termuxBridge.executeWithInput(
                command = shellCmd,
                input = command,
                onLine = { line -> handleOutputLine(line) },
                onError = { line -> onOutput?.invoke("[err] $line") }
            )

            if (cancelled) {
                _state.value = RuntimeState.Ready
                onStatus?.invoke("已中断")
                return@withContext TaskResult.Cancelled
            }

            _state.value = RuntimeState.Ready
            onStatus?.invoke("空闲")

            if (result.success) {
                TaskResult.Success("任务完成")
            } else {
                TaskResult.Failure(result.output)
            }

        } catch (e: CancellationException) {
            _state.value = RuntimeState.Ready
            onStatus?.invoke("已中断")
            TaskResult.Cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Execute failed", e)
            _state.value = RuntimeState.Error(e.message ?: "执行失败")
            onStatus?.invoke("错误")
            TaskResult.Failure(e.message ?: "未知错误")
        }
    }

    /**
     * 中断当前执行
     */
    fun cancel() {
        cancelled = true
        termuxBridge.kill()
    }

    fun destroy() {
        scope.cancel()
        termuxBridge.kill()
    }

    // ─── 内部 ─────────────────────────────────────

    private fun getRuntimeDir(): File {
        return CodeSyncManager.getInstance().getRuntimeDir()
    }

    /**
     * 从 APK assets 解压种子代码到运行时目录
     * 如果 stable_entry.py 已存在则跳过（防止覆盖 GitHub 同步的代码）
     */
    private fun extractSeedCode() {
        val targetDir = getRuntimeDir()
        if (File(targetDir, "stable_entry.py").exists()) {
            Log.d(TAG, "Seed code already extracted, skipping")
            return
        }
        targetDir.mkdirs()
        copyAssetRecursive(SEED_ASSET_DIR, targetDir)
        Log.i(TAG, "Seed code extracted from assets to ${targetDir.absolutePath}")
    }

    private fun copyAssetRecursive(assetPath: String, targetDir: File) {
        val assets = context.assets
        val list: Array<String>
        try {
            list = assets.list(assetPath) ?: return
        } catch (_: Exception) {
            return
        }

        if (list.isNotEmpty()) {
            for (item in list) {
                copyAssetRecursive("$assetPath/$item", targetDir)
            }
        } else {
            val relPath = assetPath.removePrefix("$SEED_ASSET_DIR/")
            val outFile = File(targetDir, relPath)
            outFile.parentFile?.mkdirs()
            try {
                assets.open(assetPath).use { input ->
                    outFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to extract $assetPath: ${e.message}")
            }
        }
    }

    /**
     * 处理 stable_entry.py 输出的一行
     *
     * 支持两种协议格式：
     *  1. JSONL: {"type":"step","status":"...","message":"..."}
     *  2. 旧版前缀: ACTION:/DONE:/STATUS:/ERROR:
     */
    private fun handleOutputLine(line: String) {
        when {
            line.startsWith("{") -> {
                try {
                    val json = JSONObject(line)
                    when (json.optString("type")) {
                        "step" -> {
                            val status = json.optString("status", "")
                            val message = json.optString("message", "")
                            when (status) {
                                "planning" -> onStatus?.invoke("规划中: $message")
                                "executing" -> onStatus?.invoke(message)
                                "info" -> onOutput?.invoke("[info] $message")
                                "warning" -> onOutput?.invoke("[warn] $message")
                                "done" -> {
                                    onStatus?.invoke("完成")
                                    onComplete?.invoke(TaskResult.Success(message))
                                    onOutput?.invoke("[done] $message")
                                }
                                "error" -> {
                                    onStatus?.invoke("错误")
                                    onComplete?.invoke(TaskResult.Failure(message))
                                    onOutput?.invoke("[error] $message")
                                }
                                else -> onOutput?.invoke(message)
                            }
                        }
                        "result" -> onOutput?.invoke(json.optString("content", ""))
                        "ready" -> onOutput?.invoke("[info] Agent 状态: 就绪")
                        "config_result" -> onOutput?.invoke("[config] ${json.optString("message", "")}")
                        else -> onOutput?.invoke(line)
                    }
                } catch (_: Exception) {
                    onOutput?.invoke(line)
                }
            }
            line.startsWith("ACTION:") -> {
                val json = line.removePrefix("ACTION:")
                try {
                    val request = ActionRequest(
                        type = JSONObject(json).optString("type", "unknown"),
                        target = JSONObject(json).optString("target", ""),
                        params = emptyMap()
                    )
                    onAction?.invoke(request)
                } catch (_: Exception) {}
            }
            line.startsWith("STATUS:") -> {
                onStatus?.invoke(line.removePrefix("STATUS:"))
            }
            line.startsWith("DONE:") -> {
                val msg = line.removePrefix("DONE:")
                onComplete?.invoke(TaskResult.Success(msg))
            }
            line.startsWith("ERROR:") -> {
                onOutput?.invoke("[error] ${line.removePrefix("ERROR:")}")
            }
            else -> onOutput?.invoke(line)
        }
    }
}

// ─── 类型定义 ────────────────────────────────────

data class ActionRequest(
    val type: String,
    val target: String,
    val params: Map<String, String> = emptyMap()
)

sealed class ActionResult {
    data class Success(val data: String = "") : ActionResult()
    data class Error(val message: String) : ActionResult()
}

sealed class TaskResult {
    data class Success(val message: String) : TaskResult()
    data class Failure(val error: String) : TaskResult()
    object Cancelled : TaskResult()
}
