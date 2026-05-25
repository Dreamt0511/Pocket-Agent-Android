package com.pocketagent.app.update

import android.content.Context
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
import java.io.IOException
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
 *   1. 内置 Python（APK 捆绑，ProcessBuilder 直接执行）
 *   2. Termux 路径: python/python3 (ProcessBuilder 直接执行)
 *   3. Termux 路径: python/python3 (系统 shell)
 *   4. 系统 PATH:   python → python3 → 各常见系统路径
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
                        "内置 Python 不可用，请安装 Termux 并在其中执行: pkg install python\n" +
                        "\n\nTermux: ${if (getTermuxVersion() != null) "已安装 (${getTermuxVersion()})" else "未安装"}" +
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
     *  1. 内置 Python（APK 捆绑）
     *  2. Termux 路径: python → python3 (ProcessBuilder 直接执行)
     *  3. Termux 路径: python → python3 (系统 shell)
     *  4. 系统 PATH 搜索: python → python3 → 各常见系统路径
     *
     * 记录发现方式 (pythonDiscoveryMethod)，用于后续验证时使用相同的方式。
     */
    private suspend fun discoverPython(): String? {
        diagLog.clear()

        // 0. 内置 Python（优先使用，ensureExtracted 会自动处理版本更新重新解压）
        if (BundledPythonManager.ensureExtracted(context)) {
            val bundledPy = BundledPythonManager.findPythonBinary(context)
            if (bundledPy != null) {
                isFallbackMode = false
                pythonDiscoveryMethod = "bundled"
                diagLog.add("✅ 内置 Python: $bundledPy")
                Log.i(TAG, "Using bundled Python: $bundledPy")
                return bundledPy
            }
        }
        diagLog.add("  内置 Python 不可用，尝试 Termux...")

        val termuxVer = getTermuxVersion()
        diagLog.add("Termux 版本: ${termuxVer ?: "未安装"}")
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
            "bundled" -> runBundledPython(pythonBin, listOf("--version"))
            else -> termuxBridge.execute("$pythonBin --version")
        }
    }

    /**
     * 运行内置 Python（设置 LD_LIBRARY_PATH 指向 bundled lib 目录）
     *
     * 直接 exec 失败时尝试 linker64 兜底（处理 SELinux 阻止 + linker 找不到库）。
     */
    private fun runBundledPython(pythonBin: String, args: List<String>, workDir: File? = null): CommandResult {
        // Try 1: 直接执行
        val directResult = execBundled(pythonBin, args, workDir)
        if (directResult.success || !isExecError(directResult)) {
            return directResult
        }
        // Try 2: SELinux 阻止 或 linker 找不到库，通过 system linker64 间接加载
        Log.w(TAG, "Direct exec failed, retrying via linker64...")
        return execBundledViaLinker(pythonBin, args, workDir)
    }

    /** 判断 CommandResult 是否为可兜底的执行错误 */
    private fun isExecError(result: CommandResult): Boolean {
        return result.output.contains("error=13") ||
               result.output.contains("Permission denied") ||
               result.output.contains("EACCES") ||
               result.output.contains("CANNOT LINK EXECUTABLE")
    }

    /** 直接通过 ProcessBuilder 执行 bundled Python */
    private fun execBundled(pythonBin: String, args: List<String>, workDir: File? = null): CommandResult {
        return try {
            val pb = ProcessBuilder(listOf(pythonBin) + args)
            if (workDir != null) pb.directory(workDir)
            val pythonDir = BundledPythonManager.getPythonDir(context)
            setBundledEnv(pb.environment(), pythonDir)
            val process = pb.start()
            val stdout = BufferedReader(InputStreamReader(process.inputStream))
            val stderr = BufferedReader(InputStreamReader(process.errorStream))
            val output = (stdout.readText() + stderr.readText()).trim()
            val exitCode = process.waitFor()
            CommandResult(exitCode, output, exitCode == 0)
        } catch (e: Exception) {
            CommandResult(-1, "Bundled Python error: ${e.message}", false)
        }
    }

    /**
     * 通过 system linker64 执行 bundled Python（绕过 SELinux exec 检查）
     *
     * 某些国产 ROM 的 SELinux 策略禁止 app 执行自己 data 目录下的二进制。
     * system linker64 有 system_exec 上下文，app 可以执行它；
     * linker64 加载 python3.13 时只需 read 权限（而非 exec），
     * 而 app_data_file 上下文允许 read。
     */
    private fun execBundledViaLinker(pythonBin: String, args: List<String>, workDir: File? = null): CommandResult {
        return try {
            // linker64 在 64 位系统上，32 位是 linker
            val linker = if (File("/system/bin/linker64").exists()) "/system/bin/linker64" else "/system/bin/linker"
            val cmd = listOf(linker, pythonBin) + args
            val pb = ProcessBuilder(cmd)
            if (workDir != null) pb.directory(workDir)
            val pythonDir = BundledPythonManager.getPythonDir(context)
            setBundledEnv(pb.environment(), pythonDir)
            val process = pb.start()
            val stdout = BufferedReader(InputStreamReader(process.inputStream))
            val stderr = BufferedReader(InputStreamReader(process.errorStream))
            val output = (stdout.readText() + stderr.readText()).trim()
            val exitCode = process.waitFor()
            CommandResult(exitCode, output, exitCode == 0)
        } catch (e: Exception) {
            CommandResult(-1, "Bundled Python linker error: ${e.message}", false)
        }
    }

    /**
     * 为 ProcessBuilder 设置完整的 Termux 环境变量
     */
    private fun setTermuxEnv(pb: ProcessBuilder) {
        val env = pb.environment()
        if (pythonDiscoveryMethod == "bundled") {
            setBundledEnv(env, BundledPythonManager.getPythonDir(context))
        } else {
            env["HOME"] = TermuxBootstrap.termuxRoot + "/home"
            env["PATH"] = TermuxBootstrap.termuxUsr + "/bin:" +
                    TermuxBootstrap.termuxUsr + "/bin/applets:" +
                    "/system/bin:/system/xbin"
            env["LD_LIBRARY_PATH"] = TermuxBootstrap.termuxUsr + "/lib"
            env["TMPDIR"] = TermuxBootstrap.termuxRoot + "/tmp"
        }
    }

    /**
     * 执行 Python 命令，使用与发现时相同的方式（直接 vs shell）
     */
    private suspend fun executePythonCommand(shellCmd: String): CommandResult {
        if (pythonDiscoveryMethod == "bundled") {
            // 内置 Python：ProcessBuilder + LD_LIBRARY_PATH
            var workingDir: java.io.File? = null
            var actualCommand = shellCmd
            val cdMatch = Regex("^cd (\\S+) && ").find(shellCmd)
            if (cdMatch != null) {
                workingDir = java.io.File(cdMatch.groupValues[1])
                actualCommand = shellCmd.removeRange(cdMatch.range)
            }
            val parts = actualCommand.split(" ").filter { it.isNotBlank() }
            if (parts.isEmpty()) return CommandResult(-1, "Empty command", false)
            return withContext(Dispatchers.IO) {
                val result = runBundledPython(parts.first(), parts.drop(1), workDir = workingDir)
                result
            }
        }
        if (pythonDiscoveryMethod != "direct") {
            return termuxBridge.execute(shellCmd)
        }
        // direct + bundled 模式：解析 cd 前缀，用 ProcessBuilder 运行
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
     * 为 bundled Python 创建进程，自动处理 SELinux 兜底。
     *
     * 直接 exec 被 SELinux 阻止时（国产 ROM 常见），自动通过
     * system linker64 重试（linker64 可执行，加载二进制只需 read 权限）。
     */
    private fun startBundledProcess(workingDir: java.io.File): Process {
        val cmd = listOf(pythonBin, "stable_entry.py", "--mode=task")
        val pythonDir = BundledPythonManager.getPythonDir(context)

        // Try 1: 直接 exec
        try {
            val pb = ProcessBuilder(cmd)
            pb.directory(workingDir)
            setBundledEnv(pb.environment(), pythonDir)
            return pb.start()
        } catch (e: IOException) {
            if (pythonDiscoveryMethod != "bundled" ||
                !isExecError(CommandResult(-1, e.message ?: "", false))) {
                throw e
            }
        }

        // Try 2: 通过 linker64 间接加载
        Log.w(TAG, "executePythonWithInput: 直接 exec 失败，通过 linker64 重试...")
        val linker = if (File("/system/bin/linker64").exists()) "/system/bin/linker64" else "/system/bin/linker"
        val linkerCmd = listOf(linker) + cmd
        val pb2 = ProcessBuilder(linkerCmd)
        pb2.directory(workingDir)
        setBundledEnv(pb2.environment(), pythonDir)
        return pb2.start()
    }

    /** 设置 bundled Python 的环境变量 */
    private fun setBundledEnv(env: MutableMap<String, String>, pythonDir: File) {
        val libDir = File(pythonDir, "lib").absolutePath
        env["LD_LIBRARY_PATH"] = libDir
        env["PYTHONHOME"] = pythonDir.absolutePath
        env["HOME"] = pythonDir.absolutePath
        env["PATH"] = "$libDir:$pythonDir/bin:/system/bin:/system/xbin"
        env["TMPDIR"] = context.cacheDir.absolutePath
        // 添加 site-packages（pip 安装的第三方包）
        val sitePackages = PythonDependencyManager.getSitePackagesDir(context)
        if (sitePackages.exists()) {
            val existing = env["PYTHONPATH"]
            env["PYTHONPATH"] = if (existing != null) "$existing:${sitePackages.absolutePath}"
                                 else sitePackages.absolutePath
        }
    }

    /**
     * 直接模式下的 Python 进程执行（带 stdin 写入 + stdout 流式回调）
     *
     * 如果直接 exec 被 SELinux 阻止（国产 ROM 常见），自动通过 linker64 重试。
     */
    private suspend fun executePythonWithInput(
        workingDir: java.io.File,
        input: String,
        onLine: (String) -> Unit,
        onError: (String) -> Unit
    ): CommandResult = withContext(Dispatchers.IO) {
        try {
            val process = startBundledProcess(workingDir).also { directProcess = it }

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
                "direct", "bundled" -> {
                    executePythonWithInput(runtimeDir, command, ::handleOutputLine, { line -> onOutput?.invoke("[err] $line") })
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
