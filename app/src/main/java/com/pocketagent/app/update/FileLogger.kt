package com.pocketagent.app.update

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件日志器 — 将关键日志和命令输出写入文件，便于离线诊断。
 *
 * 日志文件存储在 filesDir/logs/ 下：
 * - py_setup.log：轮转日志（上限 512KB），包含环境配置过程的关键事件
 * - pip_<name>.log：每次 pip 命令的完整 stdout/stderr 输出
 *
 * 使用方式：
 *   FileLogger.init(context)  // 应用启动时初始化
 *   FileLogger.i(TAG, "msg")  // 记录事件
 *   FileLogger.saveOutput("pip_aiohttp", output)  // 保存命令输出
 */
object FileLogger {
    private const val TAG = "FileLogger"
    private const val LOG_DIR = "logs"
    private const val LOG_FILE = "py_setup.log"
    private const val MAX_LOG_SIZE = 512L * 1024L  // 512KB

    private var logDir: File? = null

    fun init(context: Context) {
        // 优先用外部存储（Android/data/...），文件管理器可直接访问
        logDir = context.getExternalFilesDir(LOG_DIR)
            ?: File(context.filesDir, LOG_DIR) // 兜底：内部存储
        logDir?.mkdirs()
        Log.i(TAG, "日志目录: ${logDir?.absolutePath}")
    }

    fun i(tag: String, msg: String) = write('I', tag, msg)
    fun w(tag: String, msg: String) = write('W', tag, msg)
    fun e(tag: String, msg: String) = write('E', tag, msg)

    /**
     * 保存完整的命令输出到单独的文件（覆盖写入）。
     * 用于保存 pip install 等命令的完整 stdout/stderr。
     */
    fun saveOutput(name: String, output: String) {
        val dir = logDir ?: return
        val file = File(dir, "pip_${name}.log")
        file.writeText(output)
        Log.i(TAG, "输出已保存: ${file.name} (${output.length} chars)")
    }

    /**
     * 追加命令输出到已有文件。
     * 用于保存 python -c 等简短命令的输出。
     */
    fun appendOutput(name: String, output: String) {
        val dir = logDir ?: return
        val file = File(dir, "cmd_${name}.log")
        file.appendText(output + "\n")
    }

    /**
     * 读取最近的日志内容（返回最新 200 行）。
     */
    fun readRecentLogs(maxLines: Int = 200): String {
        val dir = logDir ?: return "日志未初始化"
        val file = File(dir, LOG_FILE)
        if (!file.exists()) return "日志文件不存在"
        val lines = file.readLines()
        val tail = lines.takeLast(maxLines)
        return tail.joinToString("\n")
    }

    private fun write(level: Char, tag: String, msg: String) {
        val dir = logDir ?: return
        val file = File(dir, LOG_FILE)
        rotateIfNeeded(file)
        val line = "${timestamp()} $level/$tag: $msg\n"
        file.appendText(line)
    }

    private fun timestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        return sdf.format(Date())
    }

    /**
     * 日志文件超过 MAX_LOG_SIZE 时，将当前日志重命名为 .old 并创建新文件。
     */
    private fun rotateIfNeeded(file: File) {
        if (file.exists() && file.length() > MAX_LOG_SIZE) {
            val old = File(file.parentFile, "${LOG_FILE}.1")
            file.renameTo(old)
            Log.i(TAG, "日志轮转: ${file.name} → ${old.name}")
        }
    }
}
