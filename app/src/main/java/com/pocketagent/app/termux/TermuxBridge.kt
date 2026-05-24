package com.pocketagent.app.termux

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Bridge between Android and the Termux shell.
 * 提供实时流式输出和执行命令的能力
 */
class TermuxBridge {

    companion object {
        private const val TAG = "TermuxBridge"
        private const val FALLBACK_SHELL = "/system/bin/sh"
    }

    private val _output = MutableStateFlow("")
    val output: StateFlow<String> = _output

    private var currentProcess: Process? = null

    /**
     * 执行命令并返回完整结果（同步模式）
     *
     * 始终使用 /system/bin/sh 而非 Termux 的 bash，因为 Android 10+ 禁止
     * 跨 UID 执行其它 APP 的二进制文件（SELinux 策略）。通过 PATH 环境变量
     * 指向 Termux 的 bin 目录，使系统 shell 能找到 Termux 的 Python。
     */
    suspend fun execute(command: String): CommandResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Executing: ${command.take(100)}")

        val shell = FALLBACK_SHELL

        try {
            val homeDir = TermuxBootstrap.termuxRoot + "/home"
            val workingDir = File(homeDir)
            if (!workingDir.exists()) workingDir.mkdirs()

            val processBuilder = ProcessBuilder(shell, "-c", command)
                .directory(workingDir)

            // 设置 Termux 环境变量
            val env = processBuilder.environment()
            env["HOME"] = homeDir
            env["PATH"] = TermuxBootstrap.termuxUsr + "/bin:" +
                    TermuxBootstrap.termuxUsr + "/bin/applets:" +
                    "/system/bin:/system/xbin"
            env["LD_LIBRARY_PATH"] = TermuxBootstrap.termuxUsr + "/lib"
            env["TMPDIR"] = TermuxBootstrap.termuxRoot + "/tmp"
            env["TERM"] = "xterm-256color"
            env["LANG"] = "en_US.UTF-8"

            val process = processBuilder.start()
            currentProcess = process

            val stdout = BufferedReader(InputStreamReader(process.inputStream))
            val stderr = BufferedReader(InputStreamReader(process.errorStream))

            val outputBuilder = StringBuilder()
            var line: String?

            // 逐行读取并实时更新
            while (stdout.readLine().also { line = it } != null) {
                val l = line ?: ""
                outputBuilder.appendLine(l)
                _output.value = l
            }

            // 读取错误输出
            val errorBuilder = StringBuilder()
            while (stderr.readLine().also { line = it } != null) {
                val l = line ?: ""
                errorBuilder.appendLine("[ERR] $l")
            }

            val exitCode = process.waitFor()
            currentProcess = null

            val finalOutput = if (outputBuilder.isEmpty()) {
                errorBuilder.toString().trim()
            } else {
                outputBuilder.toString().trim()
            }

            CommandResult(
                exitCode = exitCode,
                output = finalOutput,
                success = exitCode == 0
            )
        } catch (e: Exception) {
            Log.e(TAG, "Command execution failed: ${e.message}")
            currentProcess = null
            CommandResult(
                exitCode = -1,
                output = "Error: ${e.message}",
                success = false
            )
        }
    }

    /**
     * 带流式回调的执行方法
     */
    suspend fun executeWithStreaming(
        command: String,
        onLine: (String) -> Unit,
        onError: (String) -> Unit = {}
    ): CommandResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Executing (streaming): ${command.take(100)}")

        val shell = FALLBACK_SHELL

        try {
            val homeDir = TermuxBootstrap.termuxRoot + "/home"
            val processBuilder = ProcessBuilder(shell, "-c", command)
                .directory(File(homeDir))

            val env = processBuilder.environment()
            env["HOME"] = homeDir
            env["PATH"] = TermuxBootstrap.termuxUsr + "/bin:" +
                    TermuxBootstrap.termuxUsr + "/bin/applets:" +
                    "/system/bin:/system/xbin"
            env["LD_LIBRARY_PATH"] = TermuxBootstrap.termuxUsr + "/lib"
            env["TMPDIR"] = TermuxBootstrap.termuxRoot + "/tmp"
            env["TERM"] = "xterm-256color"

            val process = processBuilder.start()
            currentProcess = process

            val stdout = BufferedReader(InputStreamReader(process.inputStream))
            val stderr = BufferedReader(InputStreamReader(process.errorStream))

            val outputBuilder = StringBuilder()
            var line: String?

            // 读取 stdout 并实时回调
            while (stdout.readLine().also { line = it } != null) {
                val l = line ?: ""
                outputBuilder.appendLine(l)
                onLine(l)
            }

            // 读取 stderr
            while (stderr.readLine().also { line = it } != null) {
                val l = line ?: ""
                onError(l)
            }

            val exitCode = process.waitFor()
            currentProcess = null

            CommandResult(
                exitCode = exitCode,
                output = outputBuilder.toString().trim(),
                success = exitCode == 0
            )
        } catch (e: Exception) {
            Log.e(TAG, "Streaming execution failed: ${e.message}")
            currentProcess = null
            onError("Error: ${e.message}")
            CommandResult(
                exitCode = -1,
                output = "Error: ${e.message}",
                success = false
            )
        }
    }

    /**
     * 执行命令，写入 stdin 输入，逐行流式回调 stdout
     *
     * 适用于与 stable_entry.py 这类通过 stdin/stdout 通信的进程。
     * 写入 input 后自动关闭 stdin，进程读到 EOF 后开始工作。
     */
    suspend fun executeWithInput(
        command: String,
        input: String,
        onLine: (String) -> Unit,
        onError: (String) -> Unit = {}
    ): CommandResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "executeWithInput: ${command.take(80)}")

        val shell = FALLBACK_SHELL

        try {
            val homeDir = TermuxBootstrap.termuxRoot + "/home"
            val processBuilder = ProcessBuilder(shell, "-c", command)
                .directory(File(homeDir))

            val env = processBuilder.environment()
            env["HOME"] = homeDir
            env["PATH"] = TermuxBootstrap.termuxUsr + "/bin:" +
                    TermuxBootstrap.termuxUsr + "/bin/applets:" +
                    "/system/bin:/system/xbin"
            env["LD_LIBRARY_PATH"] = TermuxBootstrap.termuxUsr + "/lib"
            env["TMPDIR"] = TermuxBootstrap.termuxRoot + "/tmp"
            env["TERM"] = "xterm-256color"

            val process = processBuilder.start()
            currentProcess = process

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

            // stderr
            val errorBuilder = StringBuilder()
            while (stderr.readLine().also { line = it } != null) {
                val l = line ?: ""
                errorBuilder.appendLine("[ERR] $l")
                onError(l)
            }

            val exitCode = process.waitFor()
            currentProcess = null

            CommandResult(
                exitCode = exitCode,
                output = outputBuilder.toString().trim(),
                success = exitCode == 0
            )
        } catch (e: Exception) {
            Log.e(TAG, "executeWithInput failed: ${e.message}")
            currentProcess = null
            CommandResult(
                exitCode = -1,
                output = "Error: ${e.message}",
                success = false
            )
        }
    }

    /**
     * 检查 Python 是否可用
     */
    suspend fun checkPython(): Boolean = withContext(Dispatchers.IO) {
        if (!TermuxBootstrap.isReady) return@withContext false

        val pythonBin = TermuxBootstrap.termuxUsr + "/bin/python3"
        val result = execute("$pythonBin --version")
        result.success
    }

    /**
     * 检查依赖是否安装
     */
    suspend fun checkDependencies(): List<String> = withContext(Dispatchers.IO) {
        val missing = mutableListOf<String>()

        if (!TermuxBootstrap.isReady) {
            missing.add("Termux 环境未就绪")
            return@withContext missing
        }

        val pythonBin = TermuxBootstrap.termuxUsr + "/bin/python3"
        val deps = listOf("dotenv", "langchain", "rich", "requests", "aiohttp")
        for (dep in deps) {
            val result = execute("$pythonBin -c 'import $dep'")
            if (!result.success) {
                missing.add(dep)
            }
        }

        missing
    }

    fun kill() {
        currentProcess?.let {
            it.destroyForcibly()
            currentProcess = null
            Log.d(TAG, "Process killed")
        }
    }

    val isRunning: Boolean
        get() = currentProcess?.isAlive == true
}

data class CommandResult(
    val exitCode: Int,
    val output: String,
    val success: Boolean
)