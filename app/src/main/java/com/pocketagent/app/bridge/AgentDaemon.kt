package com.pocketagent.app.bridge

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.pocketagent.app.update.PythonDependencyManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Agent 守护进程：通过 stdin/stdout 管道与 Python Agent 保持常驻通信，
 * 实现真正的实时流式输出，避免每次执行都重新启动 Python 进程。
 */
class AgentDaemon(private val context: Context) {

    companion object {
        private const val TAG = "AgentDaemon"
        private const val HEARTBEAT_INTERVAL = 5000L
        private const val MAX_RETRIES = 3
        private const val EOM_MARKER = "<<<EOM>>>"

        // 全局状态流
        val daemonState = MutableStateFlow(DaemonState.STOPPED)
        val streamOutput = MutableStateFlow("")
        val diagnostics = MutableStateFlow(DiagnosticReport())
    }

    enum class DaemonState {
        STOPPED, STARTING, RUNNING, ERROR
    }

    data class DiagnosticReport(
        val pythonVersion: String = "未知",
        val pipPackages: List<String> = emptyList(),
        val configValid: Boolean = false,
        val apiReachable: Boolean = false,
        val errorMessage: String = "",
        val timestamp: Long = System.currentTimeMillis()
    )

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var errorReader: BufferedReader? = null
    private val daemonScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val outputQueue = ConcurrentLinkedQueue<String>()
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("agent_config", Context.MODE_PRIVATE)
    }

    fun initialize(): Boolean {
        daemonState.value = DaemonState.STARTING

        try {
            // 运行诊断
            runDiagnostics()

            // 启动 Python 守护进程
            val pythonPath = getPythonPath()
            val agentScript = getAgentScriptPath()
            val configPath = getConfigPath()

            val processBuilder = ProcessBuilder(
                pythonPath, agentScript,
                "--mode", "daemon",
                "--config", configPath
            )
            processBuilder.environment()["PYTHONUNBUFFERED"] = "1"
            // 添加 site-packages 到 PYTHONPATH，否则 daemon 找不到 pip 安装的第三方包
            val sitePackages = PythonDependencyManager.getSitePackagesDir(context)
            if (sitePackages.exists()) {
                processBuilder.environment()["PYTHONPATH"] = sitePackages.absolutePath
            }
            processBuilder.directory(File(agentScript).parentFile)

            process = processBuilder.start()

            writer = BufferedWriter(OutputStreamWriter(process!!.outputStream))
            reader = BufferedReader(InputStreamReader(process!!.inputStream))
            errorReader = BufferedReader(InputStreamReader(process!!.errorStream))

            // 读取 stderr 作为日志
            daemonScope.launch {
                try {
                    var line: String?
                    while (errorReader?.readLine().also { line = it } != null) {
                        if (line!!.isNotBlank()) {
                            streamOutput.value += "[log] $line\n"
                        }
                    }
                } catch (e: IOException) {
                    // 管道关闭
                }
            }

            // 持续读取 stdout 作为流式输出
            daemonScope.launch {
                try {
                    var line: String?
                    while (reader?.readLine().also { line = it } != null) {
                        if (line == EOM_MARKER) {
                            // 一条完整输出结束
                            val fullOutput = outputQueue.joinToString("")
                            outputQueue.clear()
                            streamOutput.value += fullOutput + "\n"
                        } else {
                            outputQueue.add(line!! + "\n")
                        }
                    }
                } catch (e: IOException) {
                    // 管道关闭
                }
            }

            // 心跳保活
            daemonScope.launch {
                while (isActive) {
                    delay(HEARTBEAT_INTERVAL)
                    try {
                        writer?.write("ping\n")
                        writer?.flush()
                    } catch (e: IOException) {
                        daemonState.value = DaemonState.ERROR
                        break
                    }
                }
            }

            daemonState.value = DaemonState.RUNNING
            return true

        } catch (e: Exception) {
            daemonState.value = DaemonState.ERROR
            diagnostics.value = diagnostics.value.copy(
                errorMessage = "启动失败: ${e.message}"
            )
            return false
        }
    }

    suspend fun executeCommand(prompt: String): String {
        if (daemonState.value != DaemonState.RUNNING) {
            throw IllegalStateException("Agent 守护进程未运行")
        }

        return withContext(Dispatchers.IO) {
            try {
                // 发送命令
                writer?.write("$prompt\n")
                writer?.flush()

                // 等待并累积输出
                val result = StringBuilder()
                val startTime = System.currentTimeMillis()
                val timeout = 5 * 60 * 1000L // 5 分钟超时

                while (System.currentTimeMillis() - startTime < timeout) {
                    val line = reader?.readLine() ?: break
                    if (line == EOM_MARKER) break
                    result.appendLine(line)
                    streamOutput.value = result.toString()
                    delay(10)
                }

                result.toString().ifBlank { "任务已完成，无文本输出" }
            } catch (e: IOException) {
                daemonState.value = DaemonState.ERROR
                throw RuntimeException("与 Agent 守护进程通信失败", e)
            }
        }
    }

    fun shutdown() {
        daemonScope.cancel()
        try {
            writer?.write("shutdown\n")
            writer?.flush()
        } catch (_: IOException) {}
        writer?.close()
        reader?.close()
        errorReader?.close()
        process?.destroy()
        daemonState.value = DaemonState.STOPPED
    }

    // ===== 自诊断系统 =====

    private fun runDiagnostics(): DiagnosticReport {
        var report = DiagnosticReport()

        try {
            // 1. 检测 Python 版本
            val pythonProcess = ProcessBuilder(getPythonPath(), "--version")
                .redirectErrorStream(true)
                .start()
            val version = pythonProcess.inputStream.bufferedReader().readText().trim()
            report = report.copy(pythonVersion = version)

            // 2. 检测依赖包
            val pipProcess = ProcessBuilder(getPythonPath(), "-m", "pip", "list", "--format=freeze")
                .redirectErrorStream(true)
                .start()
            val packages = pipProcess.inputStream.bufferedReader().readLines()
            report = report.copy(pipPackages = packages.filter { it.isNotBlank() })

            // 3. 检测配置文件
            val configFile = File(getConfigPath())
            report = report.copy(configValid = configFile.exists() && configFile.readText().isNotBlank())

            // 4. 检测 API 连通性
            val apiUrl = prefs.getString("api_base_url", "") ?: ""
            if (apiUrl.isNotBlank()) {
                report = report.copy(apiReachable = checkApiReachable(apiUrl))
            }

            diagnostics.value = report
        } catch (e: Exception) {
            diagnostics.value = report.copy(errorMessage = "诊断失败: ${e.message}")
        }

        return diagnostics.value
    }

    private fun checkApiReachable(url: String): Boolean {
        return try {
            val process = ProcessBuilder(
                getPythonPath(), "-c",
                "import urllib.request; urllib.request.urlopen('$url/models', timeout=5)"
            ).start()
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            process.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }

    private fun getPythonPath(): String {
        return prefs.getString("python_path", "python3") ?: "python3"
    }

    private fun getAgentScriptPath(): String {
        val baseDir = context.filesDir.absolutePath
        return "$baseDir/agent-seed/main.py"
    }

    private fun getConfigPath(): String {
        val baseDir = context.filesDir.absolutePath
        return "$baseDir/agent-seed/config.json"
    }
}