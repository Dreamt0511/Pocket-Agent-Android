package com.pocketagent.app.core

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.util.Log
import com.pocketagent.app.overlay.StreamBridge
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object TermuxLauncher {
    private const val TAG = "TermuxLauncher"
    private const val TERMUX_PACKAGE = "com.termux"
    private const val TERMUX_SERVICE = "$TERMUX_PACKAGE.app.RunCommandService"
    private const val TERMUX_RUN_COMMAND = "com.termux.RUN_COMMAND"
    private const val POCKET_AGENT_DIR = "Pocket-Agent"
    private const val GIT_REPO = "https://github.com/Dreamt0511/Pocket-Agent.git"

    /** 当 Termux 缺少悬浮窗权限时发射事件，UI 层弹窗引导授权 */
    private val _needOverlayPermission = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val needOverlayPermission = _needOverlayPermission.asSharedFlow()

    fun isTermuxInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 检查 Termux 是否有悬浮窗权限（Android 10+ 后台启动需要） */
    fun hasTermuxOverlayPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.unsafeCheckOpNoThrow(
                "android:system_alert_window",
                context.packageManager.getApplicationInfo(TERMUX_PACKAGE, 0).uid,
                TERMUX_PACKAGE
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            true // 无法检查时假设已授权，不阻塞
        }
    }

    /**
     * 启动 FastAPI 服务。
     * - 首次运行：打开 Termux 前台显示安装进度，用户可实时看到每一步
     * - 后续运行：后台启动 uvicorn，不打扰用户
     */
    fun launchFastAPI(context: Context, mirrorUrl: String = ""): Boolean {
        if (!isTermuxInstalled(context)) {
            Log.w(TAG, "Termux not installed")
            StreamBridge.error("请先安装 Termux、Termux:API、Termux:Boot")
            return false
        }

        // Android 10+ 后台启动 Termux 需要悬浮窗权限
        if (!hasTermuxOverlayPermission(context)) {
            Log.w(TAG, "Termux missing overlay permission")
            _needOverlayPermission.tryEmit(Unit)
            return false
        }

        val pipEnv = if (mirrorUrl.isNotBlank()) "PIP_INDEX_URL=$mirrorUrl " else ""

        // 共用的 uvicorn 启动脚本（初始化完成后执行）
        val uvicornPart = buildString {
            append("  # 启动 embedding 服务（可选）\n")
            append("  EMBED_MODEL=\"\$(grep '^EMBEDDING_MODEL_PATH=' ~/Pocket-Agent/.env 2>/dev/null | cut -d= -f2 | tr -d '\"')\"\n")
            append("  if [ -f \"\$EMBED_MODEL\" ]; then\n")
            append("    for pid in \$(pgrep -f 'llama-server.*8080' 2>/dev/null); do [ \"\$pid\" != \"\$\$\" ] && kill \$pid 2>/dev/null; done\n")
            append("    setsid nohup llama-server \\\n")
            append("      -m \"\$EMBED_MODEL\" \\\n")
            append("      --embedding \\\n")
            append("      -c 8192 \\\n")
            append("      --port 8080 \\\n")
            append("      --host 0.0.0.0 \\\n")
            append("      -np 4 \\\n")
            append("      -b 1024 \\\n")
            append("      -ub 1024 \\\n")
            append("      -t 4 \\\n")
            append("      </dev/null >~/llama-embed.log 2>&1 &\n")
            append("    echo \"[embed] llama-server started PID=\$!\";\n")
            append("    sleep 2\n")
            append("  fi\n")
            append("  # 启动 uvicorn\n")
            append("  for pid in \$(pgrep -f 'uvicorn.*app:app' 2>/dev/null); do [ \"\$pid\" != \"\$\$\" ] && kill \$pid 2>/dev/null; done\n")
            append("  sleep 1\n")
            append("  uvicorn app:app --host 0.0.0.0 --port 8000 &\n")
            append("  UVICORN_PID=\$!\n")
            append("  sleep 2\n")
            append("  if kill -0 \$UVICORN_PID 2>/dev/null; then\n")
            append("    echo \"[ok] Pocket Agent 启动成功 PID=\$UVICORN_PID\";\n")
            append("  else\n")
            append("    echo \"[error] Uvicorn 启动失败，请执行 cat ~/startup.log 查看详情\";\n")
            append("    exit 1\n")
            append("  fi\n")
        }

        // ── 首次初始化脚本（前台运行，用户可在 Termux 中看到进度） ──
        val initScript = buildString {
            // 已就绪则立即退出，不打扰用户
            append("if [ -f ~/.pocket-agent-ready ]; then exit 0; fi\n")
            append("clear\n")
            append("echo \"========================================\";\n")
            append("echo \"  Pocket Agent - 首次环境配置\";\n")
            append("echo \"  请耐心等待，约 3-5 分钟\";\n")
            append("echo \"========================================\";\n")
            append("echo \"\";\n")
            append("mkdir -p ~/$POCKET_AGENT_DIR\n")
            append("cd ~/$POCKET_AGENT_DIR || exit 1\n")
            append("\n")
            // 系统依赖
            append("echo \"[1/6] 更新软件源...\";\n")
            append("pkg update -y 2>&1 || true\n")
            append("echo \"\";\n")
            append("echo \"[2/6] 安装系统依赖（Python/Git/SQLite）...\";\n")
            append("pkg install -y python git sqlite libjpeg-turbo libpng libopenblas 2>&1 || true\n")
            append("echo \"\";\n")
            // pip
            append("if ! command -v pip &> /dev/null; then\n")
            append("  echo \"[3/6] 安装 pip...\";\n")
            append("  python -m ensurepip --upgrade 2>&1 || exit 1\n")
            append("else\n")
            append("  echo \"[3/6] pip 已安装，跳过\";\n")
            append("fi\n")
            append("echo \"\";\n")
            // Termux 预编译包
            append("echo \"[4/6] 安装 Termux 预编译 Python 包...\";\n")
            append("pkg install -y python-psutil python-numpy 2>&1 || true\n")
            append("echo \"\";\n")
            // Clone
            append("echo \"[5/6] 下载 Pocket Agent 代码...\";\n")
            append("if [ -d .git ] && [ ! -f requirements.txt ]; then\n")
            append("  echo \"  检测到不完整的下载，清理后重试...\";\n")
            append("  cd ~ && rm -rf ~/$POCKET_AGENT_DIR && mkdir -p ~/$POCKET_AGENT_DIR\n")
            append("  cd ~/$POCKET_AGENT_DIR || exit 1\n")
            append("fi\n")
            append("if [ ! -d .git ]; then\n")
            append("  git clone $GIT_REPO . 2>&1 || { echo \"[error] 代码下载失败，请检查网络\"; exit 1; }\n")
            append("fi\n")
            append("if [ ! -f requirements.txt ]; then\n")
            append("  echo \"[error] 代码下载不完整\"; exit 1\n")
            append("fi\n")
            append("echo \"\";\n")
            // pip install
            append("echo \"[6/6] 安装 Python 依赖...\";\n")
            append("${pipEnv}pip install fastapi uvicorn 2>&1 || { echo \"[error] FastAPI 安装失败\"; exit 1; }\n")
            append("${pipEnv}pip install -r requirements.txt 2>&1 || true\n")
            append("echo \"\";\n")
            // 验证
            append("if ! python -c \"import fastapi; import uvicorn\" 2>/dev/null; then\n")
            append("  echo \"[error] 关键依赖安装失败\"; exit 1\n")
            append("fi\n")
            append("touch ~/.pocket-agent-ready\n")
            append("echo \"========================================\";\n")
            append("echo \"  环境配置完成！正在启动服务...\";\n")
            append("echo \"========================================\";\n")
            append("echo \"\";\n")
            // 启动 uvicorn
            append(uvicornPart)
            append("echo \"\";\n")
            append("echo \"[完成] 可以返回 Pocket Agent 应用了\";\n")
        }

        // ── 快速启动脚本（后台运行，环境已就绪） ──
        val quickStartScript = buildString {
            append("{\n")
            append("  echo \"[start] 环境就绪，启动服务...\";\n")
            append("  cd ~/$POCKET_AGENT_DIR || exit 1\n")
            append(uvicornPart)
            append("} >~/startup.log 2>&1")
        }

        // 策略：先发后台快速启动（如果已就绪直接跑 uvicorn），
        // 再发前台初始化脚本（确保 Termux 打开，用户可看到进度）
        return try {
            // 1) 后台快速启动（已就绪时直接启动 uvicorn）
            val quickIntent = createRunIntent(quickStartScript, background = true)
            context.startService(quickIntent)
            Log.i(TAG, "Quick start intent sent")

            // 2) 前台初始化脚本（确保 Termux 打开显示进度）
            try {
                val initIntent = createRunIntent(initScript, background = false)
                context.startService(initIntent)
                Log.i(TAG, "Init script sent (foreground)")
            } catch (e: Exception) {
                // 前台失败（如未配置 allow-external-apps）
                // 先打开 Termux Activity 确保 Termux 可见，再用后台模式发脚本
                Log.w(TAG, "Foreground startService failed, opening Termux Activity", e)
                try {
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE)
                    if (launchIntent != null) {
                        launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launchIntent)
                    }
                } catch (_: Exception) {}
                // 用后台模式发脚本（Termux 已打开，用户切换过去能看到）
                val fallbackIntent = createRunIntent(initScript, background = true)
                context.startService(fallbackIntent)
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send Termux intent", e)
            if (e.message?.contains("allow-external-apps") == true) {
                StreamBridge.error("请在 Termux 中执行：echo 'allow-external-apps = true' >> ~/.termux/termux.properties\n然后重启 Termux 再试")
            } else {
                StreamBridge.error("无法发送命令到 Termux: ${e.message}")
            }
            false
        }
    }

    /**
     * 关闭 FastAPI 服务（杀掉 uvicorn 进程）
     */
    fun stopFastAPI(context: Context): Boolean {
        if (!isTermuxInstalled(context)) {
            Log.w(TAG, "Termux not installed")
            return false
        }

        val script = buildString {
            append("{\n")
            append("  echo \"=== Stop Pocket-Agent \$(date) ===\";\n")
            append("  for pid in \$(pgrep -f 'uvicorn.*app:app' 2>/dev/null); do\n")
            append("    [ \"\$pid\" != \"\$\$\" ] && kill \"\$pid\" 2>/dev/null && echo \"[ok] kill uvicorn PID \$pid\"\n")
            append("  done\n")
            append("  for pid in \$(pgrep -f 'llama-server.*8080' 2>/dev/null); do\n")
            append("    [ \"\$pid\" != \"\$\$\" ] && kill \"\$pid\" 2>/dev/null && echo \"[ok] kill llama-server PID \$pid\"\n")
            append("  done\n")
            append("  sleep 1\n")
            append("  echo \"[ok] Services stopped\"\n")
            append("} >~/stop.log 2>&1")
        }

        return try {
            context.startService(createRunIntent(script, background = true))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop services", e)
            false
        }
    }

    /** 创建 Termux RUN_COMMAND Intent */
    private fun createRunIntent(script: String, background: Boolean): Intent {
        return Intent(TERMUX_RUN_COMMAND).apply {
            setClassName(TERMUX_PACKAGE, TERMUX_SERVICE)
            action = TERMUX_RUN_COMMAND
            putExtra("$TERMUX_PACKAGE.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("$TERMUX_PACKAGE.RUN_COMMAND_ARGUMENTS", arrayOf("-c", script))
            putExtra("$TERMUX_PACKAGE.RUN_COMMAND_BACKGROUND", background)
        }
    }
}
