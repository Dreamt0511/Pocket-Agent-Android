package com.pocketagent.app.update

import android.content.Context
import android.util.Log
import com.pocketagent.app.update.CodeSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

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

    // ─── Termux 依赖库自愈映射 ──────────────────────
    // soname → Termux .deb 下载 URL
    // 当 assets 中缺失对应共享库时，自动从 Termux 仓库下载
    private val TERMUX_DEB_URLS = mapOf(
        "libz.so.1" to "https://packages.termux.dev/apt/termux-main/pool/main/z/zlib/zlib_1.3.2_aarch64.deb",
        "libssl.so.3" to "https://packages.termux.dev/apt/termux-main/pool/main/o/openssl/openssl_1%3A3.6.2_aarch64.deb",
        "libcrypto.so.3" to "https://packages.termux.dev/apt/termux-main/pool/main/o/openssl/openssl_1%3A3.6.2_aarch64.deb",
        "libffi.so" to "https://packages.termux.dev/apt/termux-main/pool/main/libf/libffi/libffi_3.5.2_aarch64.deb",
        "libsqlite3.so" to "https://packages.termux.dev/apt/termux-main/pool/main/libs/libsqlite/libsqlite_3.53.1_aarch64.deb",
        "libexpat.so.1" to "https://packages.termux.dev/apt/termux-main/pool/main/libe/libexpat/libexpat_2.8.1_aarch64.deb",
        "libbz2.so.1.0" to "https://packages.termux.dev/apt/termux-main/pool/main/libb/libbz2/libbz2_1.0.8-8_aarch64.deb",
        "liblzma.so.5" to "https://packages.termux.dev/apt/termux-main/pool/main/libl/liblzma/liblzma_5.8.3_aarch64.deb",
        "libandroid-posix-semaphore.so" to "https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-posix-semaphore/libandroid-posix-semaphore_0.1-4_aarch64.deb",
        "libncursesw.so.6" to "https://packages.termux.dev/apt/termux-main/pool/main/n/ncurses/ncurses_6.6.20260307%2Breally6.5.20250830_aarch64.deb",
        "libpanelw.so.6" to "https://packages.termux.dev/apt/termux-main/pool/main/n/ncurses-ui-libs/ncurses-ui-libs_6.6.20260307%2Breally6.5.20250830_aarch64.deb",
        "libgdbm.so" to "https://packages.termux.dev/apt/termux-main/pool/main/g/gdbm/gdbm_1.26-1_aarch64.deb",
        "libgdbm_compat.so" to "https://packages.termux.dev/apt/termux-main/pool/main/g/gdbm/gdbm_1.26-1_aarch64.deb",
        "libreadline.so.8" to "https://packages.termux.dev/apt/termux-main/pool/main/r/readline/readline_8.3.3_aarch64.deb",
    )

    // 共享 HTTP 客户端（复用连接，避免每次 new）
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

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
                put("TMPDIR", context.cacheDir.absolutePath)
                // 指向 Android 系统 CA 证书，否则 Termux Python 的 SSL 找不到证书
                put("SSL_CERT_DIR", "/system/etc/security/cacerts")
            }

            // Step 0: 自愈 — 确保 Termux 依赖共享库就绪
            // 若 assets 解压遗漏或本地构建缺少 .so 文件，自动从 Termux 仓库下载
            if (!ensureTermuxLibraries(context, pythonBin, baseEnv)) {
                Log.w(TAG, "依赖库自愈不完整，pip 阶段可能失败")
            }

            // Step 1: 检查 pip 是否可用（CI 已预装）
            // assets 的 site-packages 已经通过 sitecustomize.py 加入 sys.path
            // 如果 pip 还不可用，说明预装失败，尝试用 ensurepip 兜底
            Log.i(TAG, "Step 1: 检查 pip 可用性")
            val pipVersion = runPython(context, pythonBin, baseEnv,
                listOf("-m", "pip", "--version")
            )
            if (!pipVersion.success) {
                Log.w(TAG, "pip 不可用，尝试 bootstrap 兜底")
                val bootstrapCode = """
import ensurepip, sys
ensurepip.bootstrap()
sys.stdout.write("pip bootstrap done\n")
                """.trimIndent()
                val pipBootstrapResult = runPython(context, pythonBin, baseEnv,
                    listOf("-c", bootstrapCode)
                )
                Log.i(TAG, "pip bootstrap: ${pipBootstrapResult.output}")
                // 再次检查
                val pipCheck = runPython(context, pythonBin, baseEnv,
                    listOf("-m", "pip", "--version")
                )
                if (!pipCheck.success) {
                    val msg = "pip 安装失败: ${pipCheck.output}"
                    Log.e(TAG, msg)
                    _setupState.value = SetupState.Failed(msg)
                    return@withContext false
                }
                Log.i(TAG, "pip 就绪: ${pipCheck.output}")
            } else {
                Log.i(TAG, "pip 已预装: ${pipVersion.output}")
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
                    Log.w(TAG, "$pkg 二进制安装失败，尝试源码安装: ${result.output}")
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
                            firstError = "${pkg}: ${fallbackResult.output}"
                        }
                        Log.e(TAG, "安装 $pkg 失败: ${fallbackResult.output}")
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

    // ─── Termux 依赖库自愈 ──────────────────────────

    /**
     * 确保所有 Termux 依赖共享库就绪。
     *
     * Termux 依赖库不打包在 APK 中（减小体积），首次环境配置时
     * 自动从 Termux 仓库批量下载缺失的 .so 文件。
     *
     * 流程：
     * 1. 扫描 libDir 找出缺失的 soname
     * 2. 按 deb URL 去重，批量下载并提取
     * 3. canary 测试（import zlib）验证
     */
    private suspend fun ensureTermuxLibraries(
        context: Context,
        pythonBin: String,
        baseEnv: Map<String, String>
    ): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "自愈检查: Termux 依赖库...")
        val libDir = File(BundledPythonManager.getPythonDir(context), "lib")

        // Step 1: canary 测试（先快速检查，已安装则跳过）
        if (runCanaryTest(pythonBin, baseEnv, 2)) {
            Log.i(TAG, "自愈: 依赖库完整")
            return@withContext true
        }

        // Step 2: 找出缺失的 soname，按 deb URL 去重
        val missingUrls = TERMUX_DEB_URLS
            .filter { (soname, _) -> !File(libDir, soname).exists() }
            .map { (_, url) -> url }
            .distinct()

        if (missingUrls.isEmpty()) {
            Log.w(TAG, "自愈: 文件都存在但 canary 失败，可能另有原因")
            return@withContext false
        }

        Log.i(TAG, "自愈: 需下载 ${missingUrls.size} 个 deb 包（APK 升级后首次会额外下载）")

        // Step 3: 分批下载并提取（每个 deb 最多重试 2 次）
        var successCount = 0
        for ((index, url) in missingUrls.withIndex()) {
            val libName = url.substringAfterLast('/').substringBeforeLast('_')
            _setupState.value = SetupState.Installing("下载依赖库 (${index + 1}/${missingUrls.size}) $libName...")
            Log.i(TAG, "自愈: 下载 (${index + 1}/${missingUrls.size}) $url")

            var downloaded = false
            for (attempt in 1..3) {
                if (downloadAndExtractDeb(url, libDir)) {
                    downloaded = true
                    break
                }
                Log.w(TAG, "自愈: 第 $attempt 次失败，重试...")
                delay(1000L * attempt)
            }

            if (downloaded) {
                successCount++
            } else {
                Log.w(TAG, "自愈: 下载失败（重试耗尽）$url")
            }
        }

        Log.i(TAG, "自愈: 下载 $successCount/${missingUrls.size} 个 deb 包")

        // Step 4: 最终验证（等待文件系统同步，多次重试）
        delay(500)
        if (runCanaryTest(pythonBin, baseEnv, 5)) {
            Log.i(TAG, "自愈: 验证通过")
            true
        } else {
            val remaining = TERMUX_DEB_URLS.keys.filter { !File(libDir, it).exists() }
            Log.w(TAG, "自愈: 验证失败，仍有缺失: $remaining")
            false
        }
    }

    /** canary 测试：import zlib（最基础的 C 扩展模块） */
    private fun runCanaryTest(
        pythonBin: String,
        baseEnv: Map<String, String>,
        retries: Int = 1
    ): Boolean {
        for (i in 0 until retries) {
            val result = tryExec(pythonBin, listOf("-c", "import zlib; print('ok')"), baseEnv)
            if (result.success) return true
            if (i < retries - 1) Thread.sleep(500)
        }
        return false
    }

    /**
     * 从 Termux 仓库下载 .deb 并提取 .so 文件到 libDir。
     *
     * 使用纯 Kotlin 解析 ar 归档，通过系统 tar 命令提取 .tar.xz。
     * Android 10+ (API 29) 的 toybox 内置 xzcat，可直接处理。
     */
    private fun downloadAndExtractDeb(
        debUrl: String,
        libDir: File
    ): Boolean {
        val tmpDir = File(libDir, ".termux_deb_${System.nanoTime()}")
        tmpDir.mkdirs()

        try {
            // 下载 .deb
            Log.i(TAG, "下载 $debUrl")
            val request = Request.Builder().url(debUrl).build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "下载失败: HTTP ${response.code}")
                return false
            }
            val debBytes = response.body?.bytes() ?: return false

            // 解析 ar 归档 → 提取 data.tar.xz
            val dataTarXz = extractArEntry(debBytes, "data.tar.xz")
            if (dataTarXz == null) {
                Log.e(TAG, "ar 解析失败: data.tar.xz 未找到")
                return false
            }

            // 提取 .so 文件
            val extractDir = File(tmpDir, "extract")
            extractDir.mkdirs()
            val ok = extractTarXz(dataTarXz, extractDir)
            if (!ok) {
                Log.e(TAG, "tar.xz 提取失败")
                return false
            }

            // 查找并复制 .so 文件
            val count = findAndCopySoFiles(extractDir, libDir)
            Log.i(TAG, "自愈: 从 $soname 的 deb 提取了 $count 个 .so 文件")
            return count > 0

        } catch (e: Exception) {
            Log.e(TAG, "自愈: 下载/提取失败: ${e.message}")
            return false
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    /**
     * 纯 Kotlin 解析 ar 归档。
     *
     * .deb 文件是 ar 归档，包含 debian-binary、control.tar.xz、data.tar.xz。
     * 格式简单：!<arch>\n 头部后接多个 "60 字节头 + 文件数据" 条目。
     */
    private fun extractArEntry(data: ByteArray, targetName: String): ByteArray? {
        if (data.size < 8) return null
        val magic = data.copyOfRange(0, 8).toString(Charsets.US_ASCII)
        if (magic != "!<arch>\n") return null

        var offset = 8
        while (offset + 60 <= data.size) {
            val header = data.copyOfRange(offset, offset + 60)
            val name = header.copyOfRange(0, 16)
                .toString(Charsets.US_ASCII).trimEnd(' ')
            // GNU ar: 以 / 结尾表示文件名，// 为长文件名表
            val cleanName = name.trimEnd('/')
            val sizeStr = header.copyOfRange(48, 58)
                .toString(Charsets.US_ASCII).trim()
            val size = sizeStr.toIntOrNull() ?: break
            offset += 60

            if (cleanName == targetName) {
                return data.copyOfRange(offset, offset + size)
            }

            // 跳过符号表、长名表等特殊条目
            offset += size
            if (offset % 2 != 0) offset++ // ar 对齐到偶数
        }
        return null
    }

    /**
     * 提取 .tar.xz 中的 .so 文件。
     *
     * 使用系统 tar 命令（Android 10+ toybox 内置 xzcat）。
     * 若系统不支持 xz 解压，返回 false 由上游处理。
     */
    private fun extractTarXz(data: ByteArray, destDir: File): Boolean {
        val tmpFile = File(destDir.parentFile, ".tmp_data.tar.xz")

        try {
            tmpFile.writeBytes(data)

            // 方法 1: tar -xJf（toybox 内置 xzcat, Android 10+）
            if (execTar(listOf("-xJf", tmpFile.absolutePath, "-C", destDir.absolutePath))) {
                return true
            }

            // 方法 2: xzcat | tar xf -（兼容某些旧版 toybox）
            val pipeCmd = "xzcat '${tmpFile.absolutePath}' | tar xf - -C '${destDir.absolutePath}'"
            val shPb = ProcessBuilder("sh", "-c", pipeCmd)
            shPb.redirectErrorStream(true)
            val shProc = shPb.start()
            shProc.waitFor()
            if (shProc.exitValue() == 0 && destDir.listFiles()?.isNotEmpty() == true) {
                return true
            }

            Log.w(TAG, "系统不支持 xz 解压（需 Android 10+），无法自愈下载")
            return false

        } catch (e: Exception) {
            Log.e(TAG, "tar.xz 提取失败: ${e.message}")
            return false
        } finally {
            tmpFile.delete()
        }
    }

    /** 执行 tar 命令 */
    private fun execTar(args: List<String>): Boolean {
        return try {
            val pb = ProcessBuilder(listOf("tar") + args)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = BufferedReader(InputStreamReader(proc.inputStream)).readText()
            val ec = proc.waitFor()
            if (ec == 0) return true
            Log.w(TAG, "tar 返回 $ec: $out".take(200))
            false
        } catch (e: Exception) {
            Log.w(TAG, "tar 不可用: ${e.message}")
            false
        }
    }

    /** 递归查找目录中的 .so 文件并复制到目标目录 */
    private fun findAndCopySoFiles(sourceDir: File, targetLibDir: File): Int {
        var count = 0
        sourceDir.walk().forEach { file ->
            if (file.isFile && file.name.contains(".so")) {
                val dest = File(targetLibDir, file.name)
                if (!dest.exists()) {
                    file.copyTo(dest)
                    count++
                }
            }
        }
        return count
    }

    // ─── 内部 ──────────────────────────────────────

    private data class PipResult(val success: Boolean, val output: String)

    /**
     * 运行 Python 命令并返回结果。
     *
     * 先尝试直接 ProcessBuilder exec。
     * 如果被 SELinux 阻止（error=13），自动通过 system linker64 重试。
     * linker64 有 system_exec 上下文可被 app 执行，加载二进制只需 read 权限。
     */
    private fun runPython(
        context: Context,
        pythonBin: String,
        baseEnv: Map<String, String>,
        args: List<String>
    ): PipResult {
        // Try 1: 直接执行
        val directResult = tryExec(pythonBin, args, baseEnv)
        if (directResult.success || !isExecError(directResult.output)) {
            return directResult
        }
        // Try 2: 通过 linker64 间接加载（绕过 SELinux exec 检查）
        Log.w(TAG, "直接 exec 被 SELinux 阻止，通过 linker64 重试...")
        val linker = BundledPythonManager.getLinkerPath()
        return tryExec(linker, listOf(pythonBin) + args, baseEnv)
    }

    /** 尝试执行命令，返回 PipResult */
    private fun tryExec(
        binary: String,
        args: List<String>,
        env: Map<String, String>
    ): PipResult {
        return try {
            val pb = ProcessBuilder(listOf(binary) + args)
            pb.environment().putAll(env)
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

    /** 判断错误是否为 SELinux 阻止导致的执行错误 */
    private fun isExecError(output: String): Boolean {
        return output.contains("error=13") ||
               output.contains("Permission denied") ||
               output.contains("EACCES")
    }
}
