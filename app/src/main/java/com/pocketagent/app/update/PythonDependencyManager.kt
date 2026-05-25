package com.pocketagent.app.update

import android.content.Context
import android.util.Log
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
 *   → 验证 import dotenv 成功 → 标记完成
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
        data class Installing(val package: String) : SetupState()
        /** 全部完成 */
        data class Completed(val timestamp: Long) : SetupState()
        /** 失败 */
        data class Failed(val error: String) : SetupState()
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
     * 检查依赖是否已安装（尝试 import dotenv，如果失败则未就绪）
     */
    suspend fun checkReady(context: Context, pythonBin: String): Boolean = withContext(Dispatchers.IO) {
        val sitePackages = getSitePackagesDir(context)
        if (!sitePackages.exists() || !sitePackages.isDirectory) {
            return@withContext false
        }
        if (sitePackages.listFiles()?.isEmpty() != false) {
            return@withContext false
        }
        // 尝试 import dotenv 确认依赖可用
        try {
            val env = mutableMapOf<String, String>()
            val pythonDir = BundledPythonManager.getPythonDir(context)
            env["LD_LIBRARY_PATH"] = File(pythonDir, "lib").absolutePath
            env["PYTHONPATH"] = sitePackages.absolutePath
            env["PYTHONHOME"] = pythonDir.absolutePath

            val pb = ProcessBuilder(pythonBin, "-c", "import dotenv; print('ok')")
            pb.environment().putAll(env)
            val process = pb.start()
            val output = BufferedReader(InputStreamReader(process.inputStream)).readText().trim()
            val exitCode = process.waitFor()
            exitCode == 0 && output == "ok"
        } catch (e: Exception) {
            Log.w(TAG, "checkReady 异常", e)
            false
        }
    }

    /**
     * 一键安装所有依赖。
     *
     * 流程：
     * 1. 自举 pip（python3 -m ensurepip）
     * 2. 从主仓库读取 requirements.txt
     * 3. pip install --target site-packages
     * 4. 验证 import dotenv 可用
     */
    suspend fun installDependencies(
        context: Context,
        pythonBin: String,
        repoPath: String
    ): Boolean = withContext(Dispatchers.IO) {
        _setupState.value = SetupState.EnsuringPip
        Log.i(TAG, "开始安装依赖，repoPath=$repoPath")

        try {
            val pythonDir = BundledPythonManager.getPythonDir(context)
            val sitePackages = getSitePackagesDir(context)
            sitePackages.mkdirs()

            // 构建基础环境变量
            val baseEnv = mutableMapOf<String, String>().apply {
                put("LD_LIBRARY_PATH", File(pythonDir, "lib").absolutePath)
                put("PYTHONHOME", pythonDir.absolutePath)
                put("HOME", pythonDir.absolutePath)
                put("PATH", "${File(pythonDir, "bin")}:/system/bin:/system/xbin")
                put("TMPDIR", "/data/local/tmp")
            }

            // Step 1: ensurepip（如果已安装会报错，忽略）
            Log.i(TAG, "Step 1: ensurepip")
            val ensurepipResult = runPython(context, pythonBin, baseEnv,
                listOf("-m", "ensurepip", "--upgrade", "--default-pip")
            )
            if (!ensurepipResult.success && !ensurepipResult.output.contains("already satisfied")) {
                // 有些 ROM 上 ensurepip 可能不可用，尝试 get-pip.py
                Log.w(TAG, "ensurepip 失败，尝试备用方法: ${ensurepipResult.output}")
            }

            // Step 2: 准备 pip 安装参数
            val reqFile = File(repoPath, "requirements.txt")
            if (!reqFile.exists()) {
                val msg = "requirements.txt 不存在: ${reqFile.absolutePath}"
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

            for (pkg in packages) {
                _setupState.value = SetupState.Installing(pkg)
                Log.i(TAG, "安装: $pkg")

                val pipArgs = listOf(
                    "-m", "pip", "install",
                    "--target", sitePackages.absolutePath,
                    "--only-binary", ":all:",
                    "--no-input",
                    pkg
                )
                val result = runPython(context, pythonBin, baseEnv, pipArgs)
                if (!result.success) {
                    // 有些包可能没有 wheel（二进制），尝试不带 --only-binary
                    Log.w(TAG, "$pkg 二进制安装失败，尝试源码安装: ${result.output.take(200)}")
                    val fallbackArgs = listOf(
                        "-m", "pip", "install",
                        "--target", sitePackages.absolutePath,
                        "--no-input",
                        pkg
                    )
                    val fallbackResult = runPython(context, pythonBin, baseEnv, fallbackArgs)
                    if (!fallbackResult.success) {
                        val msg = "安装 $pkg 失败: ${fallbackResult.output.take(200)}"
                        Log.e(TAG, msg)
                        // 不中断，继续安装其他包
                    }
                }
            }

            // Step 4: 验证
            _setupState.value = SetupState.Installing("验证中...")
            val ready = checkReady(context, pythonBin)
            if (!ready) {
                val msg = "依赖验证失败：dotenv 不可用"
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
