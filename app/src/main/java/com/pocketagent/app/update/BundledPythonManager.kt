package com.pocketagent.app.update

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 内置 Python 管理器 — 从 APK assets 解压 Python 运行时到应用私有目录
 *
 * 将 assets/python/ 下的 Termux ARM64 Python 解压到 filesDir/python/，
 * 使得可以通过 ProcessBuilder 直接执行 Python，无需依赖 Termux 的
 * RUN_COMMAND intent（某些国产 ROM 可能阻止该 intent）。
 *
 * 资产结构：
 *   assets/python/
 *       bin/python3.13              — Python 解释器二进制
 *       lib/libpython3.13.so        — Python 共享库
 *       lib/libandroid-support.so   — Python 的 Termux 依赖库（bionic 补充）
 *       lib/python3.13/             — Python 标准库（含 lib-dynload .so 模块）
 *
 * 执行时需要设置 LD_LIBRARY_PATH 指向 python/lib/，
 * 以便动态链接器找到 libpython3.13.so 和 libandroid-support.so。
 */
object BundledPythonManager {
    private const val TAG = "BundledPython"
    private const val ASSET_ROOT = "python"
    private const val PYTHON_DIR_NAME = "python"

    // ─── 公开方法 ─────────────────────────────────

    /**
     * 返回 Python 运行时目录: filesDir/python/
     */
    fun getPythonDir(context: Context): File {
        return File(context.filesDir, PYTHON_DIR_NAME)
    }

    /**
     * 确保 Python 已解压就绪。
     *
     * 已解压则直接返回 true；否则执行解压流程。
     * 必须在 IO 线程调用（内部已切到 Dispatchers.IO）。
     */
    suspend fun ensureExtracted(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (isReady(context)) {
            Log.i(TAG, "Python 已在 ${getPythonDir(context).absolutePath} 就绪")
            return@withContext true
        }
        extract(context)
    }

    /**
     * 从 APK assets 解压 Python 运行时到应用私有目录。
     *
     * 复制以下内容：
     * 1. bin/python3.13              — Python 解释器
     * 2. lib/libpython3.13.so        — Python 共享库
     * 3. lib/libandroid-support.so   — Python 的 Termux 依赖库
     * 4. lib/python3.13/             — Python 标准库（递归）
     *
     * assets 中的符号链接无法通过 APK 打包保留，因此只复制实际文件。
     * 标记文件 (bin/python, bin/python3) 会被跳过。
     */
    suspend fun extract(context: Context): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "开始从 assets 解压内置 Python...")
        try {
            val pythonDir = getPythonDir(context)
            pythonDir.mkdirs()

            // 复制 Python 解释器二进制
            copyAssetToFile(
                context,
                "$ASSET_ROOT/bin/python3.13",
                File(pythonDir, "bin/python3.13")
            )
            Log.i(TAG, "bin/python3.13 已复制")

            // 复制 Python 共享库
            copyAssetToFile(
                context,
                "$ASSET_ROOT/lib/libpython3.13.so",
                File(pythonDir, "lib/libpython3.13.so")
            )
            Log.i(TAG, "lib/libpython3.13.so 已复制")

            // 复制 libandroid-support（Termux 依赖，否则 linker 报 CANNOT LINK EXECUTABLE）
            copyAssetToFile(
                context,
                "$ASSET_ROOT/lib/libandroid-support.so",
                File(pythonDir, "lib/libandroid-support.so")
            )
            Log.i(TAG, "lib/libandroid-support.so 已复制")

            // 递归复制 Python 标准库
            copyAssetDir(
                context,
                "$ASSET_ROOT/lib/python3.13",
                File(pythonDir, "lib/python3.13")
            )
            Log.i(TAG, "lib/python3.13/ 标准库已复制")

            // 验证解压结果
            val pythonBin = File(pythonDir, "bin/python3.13")
            if (!pythonBin.exists()) {
                Log.e(TAG, "解压失败: bin/python3.13 不存在")
                return@withContext false
            }
            if (!pythonBin.canExecute()) {
                pythonBin.setExecutable(true)
            }
            val libas = File(pythonDir, "lib/libandroid-support.so")
            if (!libas.exists()) {
                Log.e(TAG, "解压失败: lib/libandroid-support.so 不存在")
                return@withContext false
            }

            Log.i(TAG, "内置 Python 解压完成: ${pythonBin.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "解压失败", e)
            false
        }
    }

    /**
     * 返回已解压的 Python 解释器绝对路径。
     *
     * 注意：在 SELinux enforcing 的设备上，即使文件权限位允许执行，
     * app_data_file 上下文也会阻止 untrusted_app 域的 execve。
     * 调用方应通过 linker64 兜底来绕过此限制。
     *
     * @return 完整路径如 "/data/data/com.pocketagent.app/files/python/bin/python3.13"，
     *         未就绪时返回 null。
     */
    fun findPythonBinary(context: Context): String? {
        val pythonBin = File(getPythonDir(context), "bin/python3.13")
        if (!pythonBin.exists()) return null
        if (!pythonBin.canExecute()) {
            pythonBin.setExecutable(true)
        }
        return if (pythonBin.canExecute()) pythonBin.absolutePath else null
    }

    /**
     * 返回 system linker64 的路径，用于绕过 SELinux 直接 exec 限制。
     *
     * linker64 有 system_exec 上下文，app 可以执行它；
     * linker64 加载 ELF 二进制只需 read 权限，app_data_file 允许 read。
     * 64 位系统用 linker64，32 位用 linker。
     */
    fun getLinkerPath(): String {
        return if (File("/system/bin/linker64").exists()) "/system/bin/linker64"
               else "/system/bin/linker"
    }

    /**
     * 检查 Python 运行时是否已就绪。
     *
     * 就绪条件：
     * - python3.13 二进制文件存在且可执行
     * - libandroid-support.so 存在（Termux Python 的必需依赖）
     *
     * 缺少 libandroid-support.so 声明未就绪，触发重新解压。
     */
    fun isReady(context: Context): Boolean {
        val pythonBin = File(getPythonDir(context), "bin/python3.13")
        if (!pythonBin.exists() || !pythonBin.canExecute()) return false
        // libandroid-support.so 是 Python 二进制链接时必需的依赖库
        val libas = File(getPythonDir(context), "lib/libandroid-support.so")
        return libas.exists()
    }

    // ─── 内部方法 ─────────────────────────────────

    /**
     * 从 assets 复制单个文件到目标路径。
     *
     * 自动创建父目录。
     * Python 解释器二进制会被自动设置为可执行权限。
     */
    private fun copyAssetToFile(context: Context, assetPath: String, destFile: File) {
        destFile.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
        // 设置权限
        when {
            // Python 解释器二进制 → 可执行
            destFile.name == "python3.13" -> {
                destFile.setExecutable(true)
                if (!destFile.canExecute()) {
                    Log.w(TAG, "setExecutable 无效，尝试 chmod...")
                    try {
                        Runtime.getRuntime().exec(arrayOf("chmod", "700", destFile.absolutePath)).waitFor()
                    } catch (e: Exception) {
                        Log.e(TAG, "chmod 也失败: ${e.message}")
                    }
                }
                Log.i(TAG, "python3.13 可执行: ${destFile.canExecute()} (${destFile.absolutePath})")
            }
            // 共享库 (.so) 无需额外处理，linker64 只需 read 权限
        }
    }

    /**
     * 从 assets 递归复制目录到目标路径。
     *
     * 通过 assets.list() 返回值判断路径是目录还是文件：
     * - 非 null  → 目录（递归复制子项）
     * - null     → 文件（直接复制）
     */
    private suspend fun copyAssetDir(context: Context, assetDir: String, destDir: File) {
        val entries = context.assets.list(assetDir) ?: return
        destDir.mkdirs()
        for (entry in entries) {
            val assetPath = "$assetDir/$entry"
            val destFile = File(destDir, entry)
            // assets.list() 对目录返回非 null 的子项数组，对文件返回 null
            val subEntries = context.assets.list(assetPath)
            if (subEntries != null && subEntries.isNotEmpty()) {
                // 是子目录 → 递归
                copyAssetDir(context, assetPath, destFile)
            } else {
                // 是文件 → 直接复制（空目录的 list 返回空数组走此分支，但 Python 标准库没有空目录）
                copyAssetToFile(context, assetPath, destFile)
            }
        }
    }
}
