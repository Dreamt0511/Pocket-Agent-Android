package com.pocketagent.app.update

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.BufferedReader
import java.io.FileReader

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
 *
 * ⚠️ 并发安全：
 *   ensureExtracted() 会在版本不匹配时 deleteRecursively 整个目录。
 *   安装锁 (INSTALLING_LOCK_FILE) 防止在依赖安装过程中误删。
 *   所有需要 Python 的组件（PythonRuntime、PythonDependencyManager）
 *   都应先调用 ensureExtracted() 确保运行时就绪。
 */
object BundledPythonManager {
    private const val TAG = "BundledPython"
    private const val ASSET_ROOT = "python"
    private const val PYTHON_DIR_NAME = "python"
    private const val EXTRACTED_VERSION_FILE = ".extracted_version"
    /** 安装锁文件名 — installDependencies 期间创建，阻止并发删除 */
    private const val INSTALLING_LOCK_FILE = ".installing"

    // ─── 公开方法 ─────────────────────────────────

    /**
     * 返回 Python 运行时目录: filesDir/python/
     */
    fun getPythonDir(context: Context): File {
        return File(context.filesDir, PYTHON_DIR_NAME)
    }

    // ─── 安装锁 ─────────────────────────────────────
    // installDependencies 期间创建锁文件，阻止 ensureExtracted 误删

    /** 检查安装锁是否存在 */
    private fun isInstalling(context: Context): Boolean {
        return File(getPythonDir(context), INSTALLING_LOCK_FILE).exists()
    }

    /** 创建安装锁 */
    fun lockInstalling(context: Context) {
        try {
            File(getPythonDir(context), INSTALLING_LOCK_FILE).writeText("")
        } catch (_: Exception) {}
    }

    /** 释放安装锁 */
    fun unlockInstalling(context: Context) {
        try {
            File(getPythonDir(context), INSTALLING_LOCK_FILE).delete()
        } catch (_: Exception) {}
    }

    /**
     * 确保 Python 已解压就绪，版本变化时自动重新解压。
     *
     * 策略：
     * - 首次安装：直接解压
     * - 已解压且版本一致：跳过
     * - APK 升级（versionCode 增加）：删除旧文件，重新解压
     *   ⚠️ 如果安装锁存在（installDependencies 进行中），跳过删除，
     *      避免并发竞态导致二进制被删。
     *
     * 必须在 IO 线程调用（内部已切到 Dispatchers.IO）。
     */
    suspend fun ensureExtracted(context: Context): Boolean = withContext(Dispatchers.IO) {
        val pythonDir = getPythonDir(context)
        val currentVersion = getAppVersionCode(context)
        val extractedVersion = getExtractedVersion(pythonDir)

        if (isReady(context) && currentVersion == extractedVersion) {
            Log.i(TAG, "Python 就绪 (version=$currentVersion)")
            return@withContext true
        }

        // APK 升级或文件不完整 → 清理旧数据并重新解压
        if (pythonDir.exists()) {
            // ⚠️ 安装进行中 + 有锁 → 跳过删除，等下次再重新解压
            if (isInstalling(context)) {
                Log.w(TAG, "安装锁存在，跳过重新解压（等下次）")
                return@withContext isReady(context)
            }
            Log.i(TAG, "APK 版本变化 ($extractedVersion → $currentVersion) 或文件不完整，重新解压")
            pythonDir.deleteRecursively()
        }

        val ok = extract(context)
        if (ok) {
            // 记录解压版本
            pythonDir.mkdirs()
            File(pythonDir, EXTRACTED_VERSION_FILE).writeText(currentVersion.toString())
        }
        ok
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
     * 注：Termux 依赖共享库（libz.so.1 等）首次环境配置时自动下载，
     * 不在 APK assets 中，因此不在本方法中复制。
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

            // 验证 _ 前缀目录已正确解压（旧版 AAPT2 的 <dir>_* 规则会过滤此类目录）
            val pipInternal = File(pythonDir, "lib/python3.13/site-packages/pip/_internal/__init__.py")
            if (!pipInternal.exists()) {
                Log.e(TAG, "解压失败: pip/_internal/__init__.py 不存在（可能被 AAPT2 过滤）")
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
     * - pip/_internal/__init__.py 存在（验证 _ 前缀目录未被 AAPT2 过滤，
     *   旧版 APK 因 <dir>_* 默认规则导致 pip/_internal/ 目录丢失）
     *
     * 注：Termux 依赖共享库（libz.so.1 等）在首次环境配置时自动下载，
     * 不在 APK assets 中，不予检查。
     *
     * 任何条件不满足都触发重新解压。
     */
    fun isReady(context: Context): Boolean {
        val pythonBin = File(getPythonDir(context), "bin/python3.13")
        if (!pythonBin.exists() || !pythonBin.canExecute()) return false
        val libas = File(getPythonDir(context), "lib/libandroid-support.so")
        if (!libas.exists()) return false
        // 验证 _ 前缀目录未被过滤：pip/_internal/ 是 AAPT2 <dir>_* 规则的受害者
        val pipInternal = File(getPythonDir(context), "lib/python3.13/site-packages/pip/_internal/__init__.py")
        if (!pipInternal.exists()) return false
        return true
    }

    // ─── 版本管理 ─────────────────────────────────

    /**
     * 获取当前 APK 的 versionCode。
     */
    private fun getAppVersionCode(context: Context): Int {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        } catch (e: Exception) {
            Log.w(TAG, "获取 versionCode 失败: ${e.message}")
            0
        }
    }

    /**
     * 读取上次成功解压时记录的 APK 版本号。
     * 返回 null 表示从未解压过或记录文件丢失。
     */
    private fun getExtractedVersion(pythonDir: File): Int? {
        val versionFile = File(pythonDir, EXTRACTED_VERSION_FILE)
        if (!versionFile.exists()) return null
        return try {
            versionFile.readText().trim().toIntOrNull()
        } catch (e: Exception) {
            null
        }
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
