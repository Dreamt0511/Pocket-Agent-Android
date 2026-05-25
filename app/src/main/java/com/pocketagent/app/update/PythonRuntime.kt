package com.pocketagent.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.PendingIntent
import android.os.Build
import android.util.Log
import com.pocketagent.app.termux.CommandResult
import com.pocketagent.app.termux.TermuxBridge
import com.pocketagent.app.termux.TermuxBootstrap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

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
 *   1. Termux 环境: python/python3 (ProcessBuilder 直接执行)
 *   2. Termux 环境: python/python3 (系统 shell)
 *   3. Termux 环境: python/python3 (RUN_COMMAND intent，绕过跨 UID SELinux 限制)
 *   4. 系统 PATH:   python → python3 → 各常见系统路径
 *
 * 依赖：
 *   - Termux app + Python (推荐) 或系统 Python 3
 *   - 必要 pip 依赖 (Termux 模式下)
 */
class PythonRuntime(
    private val context: Context,
    private val termuxBridge: TermuxBridge
) {

    companion object {
        private const val TAG = "PythonRuntime"
        private const val SEED_ASSET_DIR = "agent-seed"
        /** Termux RUN_COMMAND intent 超时（毫秒） */
        private const val TIMEOUT_TERMUX_INTENT_MS = 15000L
        /** 系统 python3 搜索路径（按优先级） */
        private val SYSTEM_PYTHON_PATHS = listOf(
            "python",                   // Termux pkg install python 安装为 python
            "python3",                  // 部分系统提供 python3
            "/system/bin/python",
            "/system/xbin/python",
            "/system/bin/python3",
            "/system/xbin/python3",
            "/data/local/tmp/python",
            "/data/local/tmp/python3",
            "/data/data/com.termux/files/usr/bin/python",
            "/data/data/com.termux/files/usr/bin/python3",
        )
    }

    /** 解析到的 python3 二进制路径 */
    private var pythonBin: String = ""
    /** 是否降级模式 — 使用系统 python3 而非 Termux */
    private var isFallbackMode: Boolean = false
    /** 发现 Python 时使用的验证方式 — 验证阶段需用同一种方式 */
    private var pythonDiscoveryMethod: String = "shell"

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
    /** direct 模式下运行的进程，用于 cancel() */
    private var directProcess: Process? = null
    /** 发现诊断日志 */
    private val diagLog = mutableListOf<String>()

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
                val diagInfo = diagLog.joinToString("\n")
                val msg = "Python 3 未找到\n" +
                        "请安装 Termux 并在其中执行: pkg install python\n" +
                        "然后打开 Termux 设置 → 启用「允许来自外部应用的外壳命令」\n" +
                        "如果仍失败，请「重新安装 Pocket Agent」（先卸载再装，确保在 Termux 已安装之后）\n" +
                        "或前往 系统设置 → 应用 → Pocket Agent → 权限 → 开启 RUN_COMMAND" +
                        "\n\n当前状态: ${if (getTermuxVersion() != null) "Termux ${getTermuxVersion()}" else "Termux 未安装"}" +
                        if (diagInfo.isNotBlank()) "\n\n诊断信息:\n$diagInfo" else ""
                _state.value = RuntimeState.Error(msg)
                onStatus?.invoke("Python 未安装")
                return@withContext false
            }
            pythonBin = discovered
            Log.i(TAG, "Using python3: $pythonBin (fallback=$isFallbackMode)")

            // 3. 验证 Python 可用（用与发现时相同的方式，防止 SELinux 误判）
            val pythonCheck = verifyPython(pythonBin)
            if (!pythonCheck.success) {
                val msg = "Python 3 不可用 (exit=${pythonCheck.exitCode}): ${pythonCheck.output}"
                _state.value = RuntimeState.Error(msg)
                onStatus?.invoke("Python 未安装")
                return@withContext false
            }
            onStatus?.invoke("Python 就绪")

            // 4. 验证 Agent 代码（使用与发现时相同的方式执行）
            val readyResult = executePythonCommand("cd ${runtimeDir.absolutePath} && $pythonBin stable_entry.py --mode=ready")
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
     * 发现可用的 python 二进制路径
     *
     * 搜索顺序：
     *  1. Termux 路径: python → python3 (ProcessBuilder 直接执行)
     *  2. Termux 路径: python → python3 (系统 shell)
     *  3. Termux 路径: python → python3 (RUN_COMMAND intent)
     *  4. 系统 PATH 搜索: python → python3 → 各常见系统路径
     *
     * 记录发现方式 (pythonDiscoveryMethod)，用于后续验证时使用相同的方式。
     */
    private suspend fun discoverPython(): String? {
        diagLog.clear()
        val termuxVer = getTermuxVersion()
        diagLog.add("Termux 版本: ${termuxVer ?: "未安装"}")
        // 检查 RUN_COMMAND 权限是否已被授予
        val permGranted = try {
            context.packageManager.checkPermission(
                "com.termux.permission.RUN_COMMAND",
                context.packageName
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) { false }
        diagLog.add("RUN_COMMAND 权限: ${if (permGranted) "已授予" else "未授予 — 需重装 Pocket Agent 或去应用设置手动授权"}")
        // pkg install python 装的是 python 不是 python3
        val pyNames = listOf("python", "python3")
        for (pyName in pyNames) {
            val termuxPy = "${TermuxBootstrap.termuxUsr}/bin/$pyName"

            // 1. ProcessBuilder 直接执行
            try {
                val result = runPythonDirect(termuxPy)
                if (result.success) {
                    isFallbackMode = false
                    pythonDiscoveryMethod = "direct"
                    diagLog.add("✅ 方式1(direct)成功: $termuxPy")
                    Log.i(TAG, "Found Termux Python (direct): $termuxPy")
                    return termuxPy
                }
                diagLog.add("  方式1(direct)失败: exit=${result.exitCode}, out=${result.output.take(100)}")
            } catch (e: Exception) {
                diagLog.add("  方式1(direct)异常: ${e.javaClass.simpleName}: ${e.message?.take(80)}")
            }

            // 2. 系统 shell 执行 Termux python
            try {
                val r = termuxBridge.execute("$termuxPy --version")
                if (r.success) {
                    isFallbackMode = false
                    pythonDiscoveryMethod = "shell"
                    diagLog.add("✅ 方式2(shell)成功: $termuxPy")
                    Log.i(TAG, "Found Termux Python (sh): $termuxPy")
                    return termuxPy
                }
                diagLog.add("  方式2(shell)失败: exit=${r.exitCode}, out=${r.output.take(100)}")
            } catch (e: Exception) {
                diagLog.add("  方式2(shell)异常: ${e.javaClass.simpleName}: ${e.message?.take(80)}")
            }
        }

        // 2.5. Termux RUN_COMMAND intent（绕过 Android 10+ 跨 UID SELinux 限制）
        for (pyName in pyNames) {
            val termuxPy = "${TermuxBootstrap.termuxUsr}/bin/$pyName"
            try {
                val result = runViaTermuxIntent(termuxPy, listOf("--version"))
                if (result.success) {
                    isFallbackMode = false
                    pythonDiscoveryMethod = "termux_intent"
                    diagLog.add("✅ 方式3(intent)成功: $termuxPy")
                    Log.i(TAG, "Found Termux Python (intent): $termuxPy")
                    return termuxPy
                }
                diagLog.add("  方式3(intent)失败: exit=${result.exitCode}, out=${result.output.take(100)}")
            } catch (e: Exception) {
                diagLog.add("  方式3(intent)异常: ${e.javaClass.simpleName}: ${e.message?.take(80)}")
            }
        }

        // 2.6. 通过 shell 发送 am startservice intent（诊断用，不出现在发现链中，
        // 因为 am startservice 是 fire-and-forget，无法同步获取执行结果）
        // 实际运行时如果 runViaTermuxIntent 也不可用，fallback 到系统 PATH
        diagLog.add("  [诊断] 尝试通过 am startservice 发送 intent...")
        for (pyName in pyNames) {
            val termuxPy = "${TermuxBootstrap.termuxUsr}/bin/$pyName"
            try {
                val result = runViaShellIntent(termuxPy, listOf("--version"))
                diagLog.add("    am $termuxPy: exit=${result.exitCode}, out=${result.output.take(80)}")
            } catch (e: Exception) {
                diagLog.add("    am $termuxPy 异常: ${e.message?.take(60)}")
            }
        }

        // 3. 搜索系统路径
        isFallbackMode = true
        pythonDiscoveryMethod = "shell"
        diagLog.add("Termux Python 未找到，搜索系统 PATH...")
        for (path in SYSTEM_PYTHON_PATHS) {
            try {
                val result = termuxBridge.execute("$path --version")
                if (result.success) {
                    diagLog.add("✅ 系统路径成功: $path")
                    Log.i(TAG, "Found python3 via: $path")
                    return path
                }
                diagLog.add("  系统路径 $path: exit=${result.exitCode}")
            } catch (e: Exception) {
                diagLog.add("  系统路径 $path 异常: ${e.message?.take(80)}")
            }
        }

        return null
    }

    /**
     * 用与发现时相同的方式验证 python3 可用性
     */
    private suspend fun verifyPython(pythonBin: String): CommandResult {
        return when (pythonDiscoveryMethod) {
            "direct" -> runPythonDirect(pythonBin)
            "termux_intent" -> runViaTermuxIntent(pythonBin, listOf("--version"))
            else -> termuxBridge.execute("$pythonBin --version")
        }
    }

    /**
     * 为 ProcessBuilder 设置完整的 Termux 环境变量
     */
    private fun setTermuxEnv(pb: ProcessBuilder) {
        val env = pb.environment()
        env["HOME"] = TermuxBootstrap.termuxRoot + "/home"
        env["PATH"] = TermuxBootstrap.termuxUsr + "/bin:" +
                TermuxBootstrap.termuxUsr + "/bin/applets:" +
                "/system/bin:/system/xbin"
        env["LD_LIBRARY_PATH"] = TermuxBootstrap.termuxUsr + "/lib"
        env["TMPDIR"] = TermuxBootstrap.termuxRoot + "/tmp"
    }

    /**
     * 执行 Python 命令，使用与发现时相同的方式（直接 vs shell）
     */
    private suspend fun executePythonCommand(shellCmd: String): CommandResult {
        if (pythonDiscoveryMethod == "termux_intent") {
            // intent 方式不支持流式输出，批处理返回
            var wd: java.io.File? = null
            var actual = shellCmd
            val cdMatch = Regex("^cd (\\S+) && ").find(shellCmd)
            if (cdMatch != null) {
                wd = java.io.File(cdMatch.groupValues[1])
                actual = shellCmd.removeRange(cdMatch.range)
            }
            val parts = actual.split(" ").filter { it.isNotBlank() }
            if (parts.isEmpty()) return CommandResult(-1, "Empty command", false)
            val pyPath = parts.first()
            val pyArgs = parts.drop(1)
            return runViaTermuxIntent(pyPath, pyArgs, workDir = wd)
        }
        if (pythonDiscoveryMethod != "direct") {
            return termuxBridge.execute(shellCmd)
        }
        // direct 模式：解析 cd 前缀，用 ProcessBuilder 运行
        return try {
            var workingDir: java.io.File? = null
            var actualCommand = shellCmd
            val cdMatch = Regex("^cd (\\S+) && ").find(shellCmd)
            if (cdMatch != null) {
                workingDir = java.io.File(cdMatch.groupValues[1])
                actualCommand = shellCmd.removeRange(cdMatch.range)
            }
            val parts = actualCommand.split(" ").filter { it.isNotBlank() }
            if (parts.isEmpty()) return CommandResult(-1, "Empty command", false)
            val pb = ProcessBuilder(parts)
            if (workingDir != null) pb.directory(workingDir)
            setTermuxEnv(pb)
            val process = pb.start()
            val stdout = BufferedReader(InputStreamReader(process.inputStream))
            val stderr = BufferedReader(InputStreamReader(process.errorStream))
            val output = (stdout.readText() + stderr.readText()).trim()
            val exitCode = process.waitFor()
            CommandResult(exitCode, output, exitCode == 0)
        } catch (e: Exception) {
            CommandResult(-1, e.message ?: "", false)
        }
    }

    /**
     * 直接模式下的 Python 进程执行（带 stdin 写入 + stdout 流式回调）
     */
    private suspend fun executePythonWithInput(
        workingDir: java.io.File,
        input: String,
        onLine: (String) -> Unit,
        onError: (String) -> Unit
    ): CommandResult = withContext(Dispatchers.IO) {
        try {
            val pb = ProcessBuilder(pythonBin, "stable_entry.py", "--mode=task")
            pb.directory(workingDir)
            setTermuxEnv(pb)
            val process = pb.start().also { directProcess = it }

            // 写入 stdin 后关闭
            process.outputStream.write(input.toByteArray(Charsets.UTF_8))
            process.outputStream.flush()
            process.outputStream.close()

            // 逐行读取 stdout
            val stdout = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
            val stderr = BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8))

            val outputBuilder = StringBuilder()
            var line: String?
            while (stdout.readLine().also { line = it } != null) {
                val l = line ?: ""
                outputBuilder.appendLine(l)
                onLine(l)
            }

            val errorBuilder = StringBuilder()
            while (stderr.readLine().also { line = it } != null) {
                val l = line ?: ""
                errorBuilder.appendLine("[ERR] $l")
                onError(l)
            }

            val exitCode = process.waitFor()
            CommandResult(
                exitCode = exitCode,
                output = outputBuilder.toString().trim(),
                success = exitCode == 0
            )
        } catch (e: Exception) {
            CommandResult(-1, "Error: ${e.message}", false)
        }
    }

    /**
     * 直接通过 ProcessBuilder 运行 Python 二进制（不使用 shell）
     * 这对某些 Android 11+ 设备有效，因为 shell → 跨 UID 执行被 SELinux 阻止，
     * 但 ProcessBuilder 直接 exec 二进制可能绕过此限制。
     */
    private fun runPythonDirect(pythonPath: String): CommandResult {
        return try {
            val pb = ProcessBuilder(pythonPath, "--version")
            val env = pb.environment()
            env["HOME"] = TermuxBootstrap.termuxRoot + "/home"
            env["PATH"] = TermuxBootstrap.termuxUsr + "/bin:" +
                    TermuxBootstrap.termuxUsr + "/bin/applets:" +
                    "/system/bin:/system/xbin"
            env["LD_LIBRARY_PATH"] = TermuxBootstrap.termuxUsr + "/lib"
            env["TMPDIR"] = TermuxBootstrap.termuxRoot + "/tmp"
            val process = pb.start()
            val stdout = BufferedReader(InputStreamReader(process.inputStream))
            val stderr = BufferedReader(InputStreamReader(process.errorStream))
            val output = (stdout.readText() + stderr.readText()).trim()
            val exitCode = process.waitFor()
            if (exitCode != 0) Log.w(TAG, "runPythonDirect failed ($exitCode): $output")
            CommandResult(
                exitCode = exitCode,
                output = output,
                success = exitCode == 0
            )
        } catch (e: Exception) {
            Log.w(TAG, "runPythonDirect exception: ${e.message}")
            CommandResult(
                exitCode = -1,
                output = e.message ?: "",
                success = false
            )
        }
    }

    /**
     * 通过 Termux RUN_COMMAND intent API 执行 Python 命令
     *
     * 使用 Termux 的 RUN_COMMAND intent 让命令在 Termux 进程内执行，
     * 绕过 Android 10+ 跨 UID SELinux 限制。
     * 通过 PendingIntent + BroadcastReceiver 同步等待执行结果（15 秒超时）。
     *
     * 注意：此方式不支持流式输出，只返回批量结果。
     *
     * 关键要求：
     * - Termux v0.95+
     * - Termux 设置中启用「允许来自外部应用的外壳命令」
     * - intent action 必须设为 "com.termux.RUN_COMMAND"
     *   （RunCommandService.onStartCommand() 会检查 action）
     */
    private suspend fun runViaTermuxIntent(
        pythonPath: String,
        args: List<String>,
        stdin: String? = null,
        workDir: java.io.File? = null
    ): CommandResult = withContext(Dispatchers.IO) {
        val deferred = CompletableDeferred<CommandResult>()
        val requestCode = System.currentTimeMillis().toInt()
        val resultAction = "com.pocketagent.app.TERMUX_RESULT_$requestCode"

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                try {
                    if (deferred.isCompleted) return
                    // Termux v0.109+ 通过子 bundle 传递结果
                    val resultBundle = intent?.getBundleExtra("result")
                    val exitCode: Int
                    val stdout: String
                    val stderr: String
                    val errmsg: String?
                    if (resultBundle != null) {
                        exitCode = resultBundle.getInt("exitCode", -1)
                        stdout = resultBundle.getString("stdout") ?: ""
                        stderr = resultBundle.getString("stderr") ?: ""
                        errmsg = resultBundle.getString("errmsg")
                    } else {
                        // 旧版 Termux — 从 intent 顶层读取
                        exitCode = intent?.getIntExtra("com.termux.RUN_COMMAND_EXIT_CODE", -1) ?: -1
                        stdout = intent?.getStringExtra("com.termux.RUN_COMMAND_STDOUT") ?: ""
                        stderr = intent?.getStringExtra("com.termux.RUN_COMMAND_STDERR") ?: ""
                        errmsg = null
                    }
                    val output = buildString {
                        if (stdout.isNotBlank()) append(stdout.trim())
                        if (stderr.isNotBlank()) {
                            if (isNotEmpty()) append("\n")
                            append("[ERR] ").append(stderr.trim())
                        }
                        if (errmsg != null) {
                            if (isNotEmpty()) append("\n")
                            append("[TERMUX_INTERNAL] ").append(errmsg)
                        }
                    }
                    deferred.complete(
                        CommandResult(exitCode, output, exitCode == 0)
                    )
                } catch (e: Exception) {
                    deferred.complete(
                        CommandResult(-1, "Receiver error: ${e.message}", false)
                    )
                }
            }
        }

        val filter = IntentFilter(resultAction)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 需要显式声明 RECEIVER_EXPORTED（允许 Termux 跨应用发广播回来）
            context.applicationContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.applicationContext.registerReceiver(receiver, filter)
        }

        try {
            val intent = Intent("com.termux.RUN_COMMAND")
            // 先用 action 解析，不行再试 setClassName
            try {
                intent.setClassName("com.termux", "com.termux.app.RunCommandService")
            } catch (_: Exception) {}

            val cmdName = pythonPath.substringAfterLast("/")
            intent.putExtra("com.termux.RUN_COMMAND_PATH", cmdName)
            intent.putExtra(
                "com.termux.RUN_COMMAND_ARGUMENTS",
                args.toTypedArray()
            )
            intent.putExtra(
                "com.termux.RUN_COMMAND_WORKDIR",
                (workDir ?: getRuntimeDir()).absolutePath
            )
            if (stdin != null) {
                intent.putExtra("com.termux.RUN_COMMAND_STDIN", stdin)
            }
            intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            intent.putExtra(
                "com.termux.RUN_COMMAND_EXECUTION_ENVIRONMENT",
                "termux"
            )

            val resultPI = PendingIntent.getBroadcast(
                context.applicationContext,
                requestCode,
                Intent(resultAction).apply {
                    setPackage(context.packageName)
                },
                PendingIntent.FLAG_IMMUTABLE
            )
            // ★★★ 必须使用正确的 extra key ★★★
            // Termux 实际检查: "com.termux.RUN_COMMAND_PENDING_INTENT"
            intent.putExtra(
                "com.termux.RUN_COMMAND_PENDING_INTENT",
                resultPI
            )

            try {
                val started = context.startService(intent)
                if (started == null) {
                    // startService 失败（可能是 Android 14 后台限制）
                    // 尝试用 startForegroundService 并先调起 Termux Activity 唤醒进程
                    Log.w(TAG, "startService returned null, trying foreground+wake")
                    wakeTermuxAndRetry(intent, resultPI)
                }
            } catch (_: IllegalStateException) {
                // Android 8+：如果 Target SDK 26+ 且服务未在前台声明，需用 startForegroundService
                @Suppress("DEPRECATION")
                context.startForegroundService(intent)
            }

            // 给 Termux 5 秒启动时间（如果是冷启动），然后等待结果
            withTimeout(TIMEOUT_TERMUX_INTENT_MS) {
                deferred.await()
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            val termuxVer = getTermuxVersion()
            CommandResult(
                -1,
                "Termux intent 超时 (${TIMEOUT_TERMUX_INTENT_MS}ms)\n" +
                        "可能原因与解决:\n" +
                        "1. Termux 版本(${termuxVer ?: "未知"})过旧 → 从 F-Droid 安装最新版\n" +
                        "2. 未启用「允许来自外部应用的外壳命令」→ Termux 设置中开启\n" +
                        "3. Termux 后台被系统杀死 → 打开 Termux 一次再试",
                false
            )
        } catch (e: SecurityException) {
            CommandResult(-1, "Termux intent 权限不足: ${e.message}", false)
        } catch (e: Exception) {
            CommandResult(-1, "Termux intent 错误: ${e.message}", false)
        } finally {
            try {
                context.applicationContext.unregisterReceiver(receiver)
            } catch (_: Exception) {}
        }
    }

    /** 获取 Termux 版本号（用于诊断） */
    private fun getTermuxVersion(): String? {
        return try {
            val pkgInfo = context.packageManager.getPackageInfo("com.termux", 0)
            pkgInfo.versionName
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 唤醒 Termux + 重试发送 intent
     *
     * startService() 返回 null 时，可能是：
     * 1. Android 14 后台执行限制（Termux 进程不存在）
     * 2. 系统延迟绑定服务
     *
     * 策略：先尝试 startForegroundService + 调起 Termux Activity 唤醒进程，
     * 然后再次尝试 startService。
     */
    private suspend fun wakeTermuxAndRetry(
        intent: Intent,
        resultPI: PendingIntent
    ) {
        try {
            // 尝试 startForegroundService
            @Suppress("DEPRECATION")
            context.startForegroundService(intent)
            Log.i(TAG, "wakeTermuxAndRetry: startForegroundService sent")
            return
        } catch (e1: Exception) {
            Log.w(TAG, "startForegroundService failed: ${e1.message}")
        }

        try {
            // 最后手段：打开 Termux Activity 唤醒进程（用户会看到 Termux 界面闪一下）
            val wakeIntent = context.packageManager.getLaunchIntentForPackage("com.termux")
            if (wakeIntent != null) {
                wakeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(wakeIntent)
                Log.i(TAG, "wakeTermuxAndRetry: launched Termux activity")
                // 等待 Activity 启动，再试一次
                kotlinx.coroutines.delay(1000)
                context.startService(intent)
                return
            }
        } catch (e2: Exception) {
            Log.w(TAG, "wakeTermuxAndRetry all failed: ${e2.message}")
        }
    }

    /**
     * 通过 am startservice shell 命令发送 Termux RUN_COMMAND intent
     *
     * 如果 Kotlin 层的 startService() 因各种兼容性问题失败，
     * 可尝试用 shell 进程发送 intent。am 命令会通过 Android 系统服务
     * 转发 intent，不受调用者 UID 限制。
     */
    private suspend fun runViaShellIntent(
        pythonPath: String,
        args: List<String>,
        stdin: String? = null,
        workDir: java.io.File? = null
    ): CommandResult = withContext(Dispatchers.IO) {
        try {
            val wd = (workDir ?: getRuntimeDir()).absolutePath
            val argsStr = args.joinToString(" ") { "'$it'" }

            // 将 stdin 写入临时文件，通过 shell 重定向
            val stdinFile = if (stdin != null) {
                val f = java.io.File(wd, ".termux_stdin_${System.currentTimeMillis()}.txt")
                f.writeText(stdin)
                f.absolutePath
            } else null

            val amCmd = buildString {
                append("/system/bin/am startservice")
                append(" -n com.termux/com.termux.app.RunCommandService")
                append(" --es com.termux.RUN_COMMAND_PATH '$pythonPath'")
                append(" --esa com.termux.RUN_COMMAND_ARGUMENTS '$argsStr'")
                append(" --es com.termux.RUN_COMMAND_WORKDIR '$wd'")
                append(" --es com.termux.RUN_COMMAND_BACKGROUND 'true'")
                if (stdinFile != null) {
                    append(" --es com.termux.RUN_COMMAND_STDIN \"\$(cat '$stdinFile')\"")
                }
            }

            val result = termuxBridge.execute(amCmd)

            // 清理临时文件
            if (stdinFile != null) {
                java.io.File(stdinFile).delete()
            }

            // am startservice 返回 0 仅表示 intent 已送达，不表示命令执行成功
            // 我们需要额外的轮询或文件机制来获取结果
            // 这里只用作诊断，不适合实际生产
            CommandResult(
                exitCode = if (result.success) 0 else -1,
                output = result.output,
                success = result.success
            )
        } catch (e: Exception) {
            CommandResult(-1, "Shell intent error: ${e.message}", false)
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
            val shellCmd = "cd ${runtimeDir.absolutePath} && $pythonBin stable_entry.py --mode=task"

            val result = when (pythonDiscoveryMethod) {
                "direct" -> {
                    executePythonWithInput(runtimeDir, command, ::handleOutputLine, { line -> onOutput?.invoke("[err] $line") })
                }
                "termux_intent" -> {
                    // intent 不支持流式输出，批处理：执行后按行解析输出
                    val intentResult = runViaTermuxIntent(
                        pythonPath = pythonBin,
                        args = listOf("stable_entry.py", "--mode=task"),
                        stdin = command,
                        workDir = runtimeDir
                    )
                    intentResult.output.lines().forEach { line ->
                        if (line.isNotBlank()) handleOutputLine(line)
                    }
                    intentResult
                }
                else -> {
                    termuxBridge.executeWithInput(
                        command = shellCmd,
                        input = command,
                        onLine = { line -> handleOutputLine(line) },
                        onError = { line -> onOutput?.invoke("[err] $line") }
                    )
                }
            }

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
        directProcess?.let { if (it.isAlive) it.destroyForcibly() }
        directProcess = null
    }

    fun destroy() {
        scope.cancel()
        termuxBridge.kill()
        directProcess?.let { if (it.isAlive) it.destroyForcibly() }
        directProcess = null
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
