package com.pocketagent.app.core

import android.content.Context
import android.content.Intent
import android.util.Log

object TermuxLauncher {
    private const val TAG = "TermuxLauncher"
    private const val TERMUX_PACKAGE = "com.termux"
    private const val TERMUX_SERVICE = "$TERMUX_PACKAGE.app.RunCommandService"
    private const val TERMUX_RUN_COMMAND = "com.termux.RUN_COMMAND"
    private const val POCKET_AGENT_DIR = "Pocket-Agent"
    private const val GIT_REPO = "https://github.com/Dreamt0511/Pocket-Agent.git"

    fun isTermuxInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 启动 FastAPI 服务。环境初始化（clone + pip install）只执行一次，
     * 后续点启动直接跑 uvicorn。
     */
    fun launchFastAPI(context: Context, mirrorUrl: String = ""): Boolean {
        if (!isTermuxInstalled(context)) {
            Log.w(TAG, "Termux not installed")
            StreamBridge.error("请先安装 Termux、Termux:API、Termux:Boot")
            return false
        }

        val pipEnv = if (mirrorUrl.isNotBlank()) "PIP_INDEX_URL=$mirrorUrl " else ""

        // 环境初始化 + 启动 uvicorn。第一次会安装 Python + git clone + pip install，
        // 完成后创建 ~/.pocket-agent-ready，后续启动跳过初始化。
        val script = buildString {
            append("{\n")
            append("  echo \"=== Pocket-Agent \$(date) ===\";\n")
            append("  mkdir -p ~/$POCKET_AGENT_DIR\n")
            append("  cd ~/$POCKET_AGENT_DIR || exit 1\n")
            append("  if [ ! -f ~/.pocket-agent-ready ]; then\n")
            append("    echo \"[init] First run — setting up environment...\";\n")
            append("    # 检查并安装 Python 和系统依赖\n")
            append("    if ! command -v python &> /dev/null; then\n")
            append("      echo \"[init] Installing Python and system dependencies...\";\n")
            append("      pkg update -y 2>&1 || exit 1\n")
            append("      pkg install -y python git sqlite libjpeg-turbo libpng libopenblas 2>&1 || exit 1\n")
            append("    fi\n")
            append("    # 检查并安装 pip\n")
            append("    if ! command -v pip &> /dev/null; then\n")
            append("      echo \"[init] Installing pip...\";\n")
            append("      python -m ensurepip --upgrade 2>&1 || exit 1\n")
            append("    fi\n")
            append("    if [ ! -d .git ]; then\n")
            append("      git clone $GIT_REPO . || exit 1\n")
            append("    fi\n")
            append("    echo \"[init] Installing fastapi+uvicorn...\";\n")
            append("    ${pipEnv}pip install -q fastapi uvicorn 2>&1 || exit 1;\n")
            append("    echo \"[init] Installing requirements.txt...\";\n")
            append("    ${pipEnv}pip install -q -r requirements.txt 2>&1 || exit 1;\n")
            append("    touch ~/.pocket-agent-ready\n")
            append("    echo \"[init] Done — ready for future starts\";\n")
            append("  else\n")
            append("    echo \"[start] Environment ready, launching uvicorn...\";\n")
            append("  fi\n")
            append("  # 启动 embedding 服务（BGE-M3，需编译带 embedding 后端的 llama.cpp）\n")
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
            append("  else\n")
            append("    echo \"[embed] Model not found, skip embedding service\";\n")
            append("  fi\n")
            append("  echo \"[uvicorn] Starting...\";\n")
            append("  for pid in \$(pgrep -f 'uvicorn.*app:app' 2>/dev/null); do [ \"\$pid\" != \"\$\$\" ] && kill \$pid 2>/dev/null; done\n")
            append("  sleep 1\n")
            append("  # 先尝试启动 uvicorn，如果失败则输出错误信息\n")
            append("  uvicorn app:app --host 0.0.0.0 --port 8000 &\n")
            append("  UVICORN_PID=\$!\n")
            append("  sleep 2\n")
            append("  # 检查 uvicorn 是否成功启动\n")
            append("  if kill -0 \$UVICORN_PID 2>/dev/null; then\n")
            append("    echo \"[ok] Uvicorn started PID=\$UVICORN_PID\";\n")
            append("  else\n")
            append("    echo \"[error] Uvicorn failed to start. Check ~/uvicorn.log for details.\";\n")
            append("    echo \"[error] Last 10 lines of uvicorn.log:\";\n")
            append("    tail -10 ~/uvicorn.log 2>/dev/null || echo \"No log file found\";\n")
            append("    exit 1;\n")
            append("  fi\n")
            append("} 2>&1 | tee ~/startup.log")
        }

        return sendScript(context, script)
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

        return sendScript(context, script)
    }

    /** 抽取的公共方法：发送 bash 脚本到 Termux 执行 */
    private fun sendScript(context: Context, script: String): Boolean {
        val intent = Intent(TERMUX_RUN_COMMAND).apply {
            setClassName(TERMUX_PACKAGE, TERMUX_SERVICE)
            action = TERMUX_RUN_COMMAND
            putExtra("$TERMUX_PACKAGE.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("$TERMUX_PACKAGE.RUN_COMMAND_ARGUMENTS", arrayOf("-c", script))
            putExtra("$TERMUX_PACKAGE.RUN_COMMAND_BACKGROUND", true)
        }

        return try {
            context.startService(intent)
            Log.i(TAG, "Termux intent sent")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send Termux intent", e)
            // 检查是否是 allow-external-apps 配置问题
            if (e.message?.contains("allow-external-apps") == true) {
                StreamBridge.error("请在 Termux 中执行：echo 'allow-external-apps = true' >> ~/.termux/termux.properties")
            } else {
                StreamBridge.error("无法发送命令到 Termux: ${e.message}")
            }
            false
        }
    }
}
