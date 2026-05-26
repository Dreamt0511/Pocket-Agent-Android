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
 * 日志存储在 filesDir/logs/（内部存储，稳定可靠）：
 * - py_setup.log：轮转日志（上限 512KB），环境配置过程的事件
 * - pip_<name>.log：每次 pip 命令的完整 stdout/stderr
 *
 * 所有写操作都有 try-catch 保护，不会因日志写入失败影响主流程。
 */
object FileLogger {
    private const val TAG = "FileLogger"
    private const val LOG_DIR = "logs"
    private const val LOG_FILE = "py_setup.log"
    private const val MAX_LOG_SIZE = 512L * 1024L  // 512KB

    private var logDir: File? = null

    fun init(context: Context) {
        // 始终用内部存储（filesDir），避免外部存储权限/路径问题
        logDir = File(context.filesDir, LOG_DIR)
        try {
            logDir?.mkdirs()
        } catch (e: Exception) {
            Log.w(TAG, "创建日志目录失败: ${e.message}")
        }
        Log.i(TAG, "日志目录: ${logDir?.absolutePath}")
    }

    /** 外部可直接读取的日志路径（用于在错误信息中展示） */
    fun getLogPath(): String {
        return if (logDir != null) File(logDir, LOG_FILE).absolutePath else "(未初始化)"
    }

    fun i(tag: String, msg: String) = write('I', tag, msg)
    fun w(tag: String, msg: String) = write('W', tag, msg)
    fun e(tag: String, msg: String) = write('E', tag, msg)

    /** 保存完整命令输出到单独文件，覆盖写入 */
    fun saveOutput(name: String, output: String) {
        val dir = logDir ?: return
        try {
            File(dir, "pip_${name}.log").writeText(output)
        } catch (e: Exception) {
            Log.w(TAG, "保存输出失败: ${e.message}")
        }
    }

    /** 读取最近日志（最多 maxLines 行） */
    fun readRecentLogs(maxLines: Int = 200): String {
        val dir = logDir ?: return "日志未初始化"
        val file = File(dir, LOG_FILE)
        if (!file.exists()) return "日志文件不存在"
        return try {
            file.readLines().takeLast(maxLines).joinToString("\n")
        } catch (e: Exception) {
            "读取日志失败: ${e.message}"
        }
    }

    private fun write(level: Char, tag: String, msg: String) {
        val dir = logDir ?: return
        try {
            val file = File(dir, LOG_FILE)
            rotateIfNeeded(file)
            val line = "${timestamp()} $level/$tag: $msg\n"
            file.appendText(line)
        } catch (e: Exception) {
            Log.w(TAG, "写入日志失败: ${e.message}")
        }
    }

    private fun timestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        return sdf.format(Date())
    }

    private fun rotateIfNeeded(file: File) {
        if (file.exists() && file.length() > MAX_LOG_SIZE) {
            try {
                val old = File(file.parentFile, "${LOG_FILE}.1")
                file.renameTo(old)
            } catch (e: Exception) {
                Log.w(TAG, "日志轮转失败: ${e.message}")
            }
        }
    }
}
