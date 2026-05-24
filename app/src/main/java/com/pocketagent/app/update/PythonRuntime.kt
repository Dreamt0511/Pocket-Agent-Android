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
 * Python 运行时管理器 — 通过 Python 执行动态加载的主仓库代码
 *
 * 架构：
 *   Android (Kotlin)  ←→  Python (stable_entry.py)
 *          │                         │
 *          │  execute("帮我去...")     │ 通过 stdin 下发用户指令
 *          │  stable_entry.py         │ 通过 stdout 输出 JSONL
 *          │  onOutput ←───────────  │ 流式输出回调
 *          │  onStatus ←───────────  │ 状态更新回调
 *
 * Python 发现顺序：
 *   1. Termux 环境: /data/data/com.termux/files/usr/bin/python3
 *   2. 系统 PATH:   python3 (通过 /system/bin/sh)
 *   3. 常见系统路径: /system/bin/python3, /system/xbin/python3 等
 *
 * 依赖：
 *   - Termux app + Python 3 (推荐) 或系统 Python 3
 *   - 必要 pip 依赖 (Termux 模式下)
 */
class PythonRuntime(
    private val context: Context,
    private val termuxBridge: TermuxBridge
) {

    companion object {
        private const val TAG = "PythonRuntime"
        private const val SEED_ASSET_DIR = "agent-seed"
        /** 系统 python3 搜索路径（按优先级） */
        private val SYSTEM_PYTHON_PATHS = listOf(
            "python3",                  // 通过 shell PATH 查找
            "/system/bin/python3",
            "/system/xbin/python3",
            "/data/local/tmp/python3",
            "/data/data/com.termux/files/usr/bin/python3",
        )
    }

    /** 解析到的 python3 二进制路径 */
    private var pythonBin: String = ""
    /** 是否降级模式 — 使用系统 python3 而非 Termux */
    private var isFallbackMode: Boolean = false

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
     *  1. 从 APK assets 解压种子代码到运行时目录（无论 Python 是否可用）
     *  2. 发现可用的 python3（先找 Termux，再找系统路径）
     *  3. 验证 Python 可用
     *  4. 验证 Agent 代码就绪 (--mode=ready)，降级模式下跳过此步骤
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        _state.value = RuntimeState.Initializing
        onStatus?.invoke("初始化 Python 环境...")

        try {
            // 1. 解压种子代码（无论 Python 是否可用，都要确保技能文件存在）
            extractSeedCode()
            val runtimeDir = getRuntimeDir()
            Log.i(TAG, "Seed code ready at ${runtimeDir.absolutePath}")

            // 2. 发现 python3 路径
            val discovered = discoverPython()
            if (discovered == null) {
                val msg = "Python 3 未找到\n" +
                        "请安装 Termux 并在其中执行: pkg install python\n" +
                        "或者将 python3 放入系统 PATH"
                _state.value = RuntimeState.Error(msg)
                onStatus?.invoke("Python 未安装")
                return@withContext false
            }
            pythonBin = discovered
            Log.i(TAG, "Using python3: $pythonBin (fallback=$isFallbackMode)")

            // 3. 验证 Python 可用
            val pythonCheck = termuxBridge.execute("$pythonBin --version")
            if (!pythonCheck.success) {
                val msg = "Python 3 不可用 (exit=${pythonCheck.exitCode}): ${pythonCheck.output}"
                _state.value = RuntimeState.Error(msg)
                onStatus?.invoke("Python 未安装")
                return@withContext false
            }
            onStatus?.invoke("Python 就绪")

            // 4. 验证 Agent 代码
            val readyCmd = "cd ${runtimeDir.absolutePath} && $pythonBin stable_entry.py --mode=ready"
            val readyResult = termuxBridge.execute(readyCmd)
            if (!readyResult.success) {
                if (isFallbackMode) {
                    // 降级模式: 系统 python 可能缺少 pip 包，跳过校验
                    Log.w(TAG, "Agent ready check skipped (fallback mode): ${readyResult.output}")
                } else {
                    val msg = "Agent 验证失败: ${readyResult.output}"
                    _state.value = RuntimeState.Error(msg)
                    onStatus?.invoke("Agent 代码异常")
                    return@withContext false
                }
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
     * 发现可用的 python3 二进制路径
     *
     * 搜索顺序：
     *  1. Termux: /data/data/com.termux/files/usr/bin/python3（最完备的包环境）
     *  2. 通过 shell PATH 查找 python3
     *  3. 常见系统路径
     */
    private suspend fun discoverPython(): String? {
        // 1. 优先使用 Termux 的 Python（包环境最全）
        if (TermuxBootstrap.isReady) {
            val termuxPy = "${TermuxBootstrap.termuxUsr}/bin/python3"
            try {
                val r = termuxBridge.execute("$termuxPy --version")
                if (r.success) {
                    isFallbackMode = false
                    Log.i(TAG, "Found Termux Python at $termuxPy")
                    return termuxPy
                }
            } catch (_: Exception) {}
        }

        // 2. Termux 不可用，搜索系统路径
        isFallbackMode = true
        Log.i(TAG, "Termux not available, searching system python3...")

        for (path in SYSTEM_PYTHON_PATHS) {
            try {
                val result = termuxBridge.execute("$path --version")
                if (result.success) {
                    Log.i(TAG, "Found python3 via: $path")
                    return path
                }
            } catch (_: Exception) {}
        }

        return null
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
        targetDir.mkdirs()

        // 始终提取 .env.example 模板（配置页面需要）
        val envExample = File(targetDir, ".env.example")
        if (!envExample.exists()) {
            copyAssetRecursive("$SEED_ASSET_DIR/.env.example", targetDir)
            Log.i(TAG, ".env.example extracted to ${envExample.absolutePath}")
        }

        // 主代码：已存在则跳过（防止覆盖 GitHub 同步的代码）
        if (File(targetDir, "stable_entry.py").exists()) {
            Log.d(TAG, "Seed code already extracted, skipping")
            return
        }
        copyAssetRecursive(SEED_ASSET_DIR, targetDir)
        Log.i(TAG, "Seed code extracted from assets to ${targetDir.absolutePath}")
    }

    private fun copyAssetRecursive(assetPath: String, targetDir: File) {
        val assets = context.assets
        // 先尝试作为目录列出
        val list: Array<String>?
        try {
            list = assets.list(assetPath)
        } catch (_: Exception) {
            return
        }

        if (list != null && list.isNotEmpty()) {
            // 是目录 → 递归子项
            for (item in list) {
                copyAssetRecursive("$assetPath/$item", targetDir)
            }
        } else {
            // list == null 或空 → 尝试作为文件打开
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
                // 既不是目录也不是文件（路径不存在），静默跳过
                Log.w(TAG, "Skipping $assetPath: ${e.message}")
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
