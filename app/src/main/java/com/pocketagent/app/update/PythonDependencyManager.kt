package com.pocketagent.app.update

import android.content.Context
import android.util.Log
import com.pocketagent.app.update.CodeSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Python 依赖管理器 — 使用内置 Python 的 pip 安装第三方包
 *
 * 架构：
 *   首页 "环境配置" → ensurepip 自举 pip → pip install -r requirements.txt
 *   → 验证 site-packages 有 .dist-info → 标记完成
 *
 * 依赖安装到 filesDir/python/site-packages/，通过 PYTHONPATH 引入。
 * 这样 APK 体积不受影响，依赖在设备本地安装，更新主库代码后重新配置即可。
 */
object PythonDependencyManager {
    private const val TAG = "PyDependencyMgr"
    private const val SITE_PACKAGES_DIR = "python/site-packages"

    // ─── 状态 ──────────────────────────────────────

    sealed class SetupState {
        /** 未开始 */
        object Idle : SetupState()
        /** 正在自举 pip */
        object EnsuringPip : SetupState()
        /** 正在安装依赖（显示当前包名） */
        class Installing(val pkg: String) : SetupState()
        /** 全部完成 */
        class Completed(val timestamp: Long) : SetupState()
        /** 失败 */
        class Failed(val error: String) : SetupState()
    }

    private val _setupState = MutableStateFlow<SetupState>(SetupState.Idle)
    val setupState: StateFlow<SetupState> = _setupState

    // ─── 公开方法 ──────────────────────────────────

    /**
     * 返回 site-packages 目录: filesDir/python/site-packages/
     */
    fun getSitePackagesDir(context: Context): File {
        return File(context.filesDir, SITE_PACKAGES_DIR)
    }

    /**
     * 检查依赖是否已安装（检查 site-packages 中是否有已安装的包）
     */
    suspend fun checkReady(context: Context): Boolean = withContext(Dispatchers.IO) {
        val sitePackages = getSitePackagesDir(context)
        if (!sitePackages.exists() || !sitePackages.isDirectory) {
            return@withContext false
        }
        // 检查是否存在 .dist-info 目录（pip 安装后留下的元数据）
        val files = sitePackages.listFiles() ?: return@withContext false
        files.any { it.name.endsWith(".dist-info") }
    }

    /**
     * 一键安装所有依赖。
     *
     * 流程：
     * 1. 自举 pip（python3 -m ensurepip）
     * 2. 从已同步的代码仓库读取 requirements.txt
     * 3. pip install --target site-packages
     * 4. 验证 site-packages 有 .dist-info
     *
     * 注意：依赖安装前必须先通过 CodeSyncManager 完成代码同步，
     * 否则运行时目录下没有 requirements.txt 会失败。
     */
    suspend fun installDependencies(
        context: Context,
        pythonBin: String
    ): Boolean = withContext(Dispatchers.IO) {
        _setupState.value = SetupState.EnsuringPip
        Log.i(TAG, "开始安装依赖")

        try {
            val pythonDir = BundledPythonManager.getPythonDir(context)
            val sitePackages = getSitePackagesDir(context)
            sitePackages.mkdirs()

            // 获取已同步的代码目录（需要先完成代码同步）
            val runtimeDir = try {
                CodeSyncManager.getInstance().getRuntimeDir()
            } catch (_: IllegalStateException) {
                val msg = "代码同步引擎未初始化，请先返回首页等待同步完成"
                Log.e(TAG, msg)
                _setupState.value = SetupState.Failed(msg)
                return@withContext false
            }
            val reqFile = File(runtimeDir, "requirements.txt")

            // 构建基础环境变量
            val baseEnv = mutableMapOf<String, String>().apply {
                put("LD_LIBRARY_PATH", File(pythonDir, "lib").absolutePath)
                put("PYTHONHOME", pythonDir.absolutePath)
                put("HOME", pythonDir.absolutePath)
                put("PATH", "${File(pythonDir, "bin")}:/system/bin:/system/xbin")
                put("TMPDIR", "/data/local/tmp")
                // 指向 Android 系统 CA 证书，否则 Termux Python 的 SSL 找不到证书
                put("SSL_CERT_DIR", "/system/etc/security/cacerts")
            }

            // Step 1: ensurepip（如果已安装会报错，忽略）
            Log.i(TAG, "Step 1: ensurepip")
            val ensurepipResult = runPython(context, pythonBin, baseEnv,
                listOf("-m", "ensurepip", "--upgrade", "--default-pip")
            )
            if (!ensurepipResult.success && !ensurepipResult.output.contains("already satisfied")) {
                Log.w(TAG, "ensurepip 失败，尝试备用方法: ${ensurepipResult.output}")
            }

            // Step 2: 检查 requirements.txt
            if (!reqFile.exists()) {
                val msg = "requirements.txt 不存在（请先同步代码）: ${reqFile.absolutePath}"
                Log.e(TAG, msg)
                _setupState.value = SetupState.Failed(msg)
                return@withContext false
            }

            // Step 3: pip install
            _setupState.value = SetupState.Installing("准备安装...")
            Log.i(TAG, "Step 2: pip install -r ${reqFile.absolutePath}")

            // 先读取 requirements.txt 列出包名用于进度显示
            val packages = reqFile.readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { it.split(Regex("[>=<\\[;]")).first().trim() }
                .filter { it.isNotBlank() }

            var firstError: String? = null

            for (pkg in packages) {
                _setupState.value = SetupState.Installing(pkg)
                Log.i(TAG, "安装: $pkg")

                val pipArgs = listOf(
                    "-m", "pip", "install",
                    "--target", sitePackages.absolutePath,
                    "--trusted-host", "pypi.org",
                    "--trusted-host", "files.pythonhosted.org",
                    "--only-binary", ":all:",
                    "--no-input",
                    pkg
                )
                val result = runPython(context, pythonBin, baseEnv, pipArgs)
                if (!result.success) {
                    Log.w(TAG, "$pkg 二进制安装失败，尝试源码安装: ${result.output.take(200)}")
                    val fallbackArgs = listOf(
                        "-m", "pip", "install",
                        "--target", sitePackages.absolutePath,
                        "--trusted-host", "pypi.org",
                        "--trusted-host", "files.pythonhosted.org",
                        "--no-input",
                        pkg
                    )
                    val fallbackResult = runPython(context, pythonBin, baseEnv, fallbackArgs)
                    if (!fallbackResult.success) {
                        if (firstError == null) {
                            firstError = "${pkg}: ${fallbackResult.output.take(200)}"
                        }
                        Log.e(TAG, "安装 $pkg 失败: ${fallbackResult.output.take(200)}")
                    }
                }
            }

            // Step 4: 验证
            _setupState.value = SetupState.Installing("验证中...")
            val ready = checkReady(context)
            if (!ready) {
                val msg = if (firstError != null) {
                    "安装失败: $firstError"
                } else {
                    "依赖验证失败: site-packages 中没有已安装的包"
                }
                Log.e(TAG, msg)
                _setupState.value = SetupState.Failed(msg)
                return@withContext false
            }

            _setupState.value = SetupState.Completed(System.currentTimeMillis())
            Log.i(TAG, "依赖安装完成")
            true

        } catch (e: Exception) {
            val msg = "安装异常: ${e.message}"
            Log.e(TAG, msg, e)
            _setupState.value = SetupState.Failed(msg)
            false
        }
    }

    /** 重置状态为 Idle */
    fun resetState() {
        _setupState.value = SetupState.Idle
    }

    // ─── 内部 ──────────────────────────────────────

    private data class PipResult(val success: Boolean, val output: String)

    /**
     * 运行 Python 命令并返回结果。
     * 使用 ProcessBuilder 直接执行，设置必要的环境变量。
     */
    private fun runPython(
        context: Context,
        pythonBin: String,
        baseEnv: Map<String, String>,
        args: List<String>
    ): PipResult {
        return try {
            val pb = ProcessBuilder(listOf(pythonBin) + args)
            val env = pb.environment()
            env.putAll(baseEnv)
            val process = pb.start()
            val stdout = BufferedReader(InputStreamReader(process.inputStream))
            val stderr = BufferedReader(InputStreamReader(process.errorStream))
            val output = (stdout.readText() + "\n" + stderr.readText()).trim()
            val exitCode = process.waitFor()
            PipResult(exitCode == 0, output)
        } catch (e: Exception) {
            PipResult(false, e.message ?: "")
        }
    }
}
