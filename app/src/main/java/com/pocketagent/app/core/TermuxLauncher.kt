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
            return false
        }

        val pipEnv = if (mirrorUrl.isNotBlank()) "PIP_INDEX_URL=$mirrorUrl " else ""

        // 环境初始化 + 启动 uvicorn。第一次会 git clone + pip install，
        // 完成后创建 ~/.pocket-agent-ready，后续启动跳过初始化。
        val script = buildString {
            append("cd && {\n")
            append("  echo \"=== Pocket-Agent \$(date) ===\";\n")
            append("  if [ ! -f ~/.pocket-agent-ready ]; then\n")
            append("    echo \"[init] First run — setting up environment...\";\n")
            append("    if [ ! -d ~/$POCKET_AGENT_DIR/.git ]; then\n")
            append("      git clone $GIT_REPO ~/$POCKET_AGENT_DIR || exit 1;\n")
            append("    else\n")
            append("      cd ~/$POCKET_AGENT_DIR && git pull origin main || true;\n")
            append("    fi &&\n")
            append("    cd ~/$POCKET_AGENT_DIR &&\n")
            append("    echo \"[init] Installing fastapi+uvicorn...\";\n")
            append("    ${pipEnv}pip install -q fastapi uvicorn 2>&1 || exit 1;\n")
            append("    echo \"[init] Installing requirements.txt...\";\n")
            append("    ${pipEnv}pip install -q -r requirements.txt 2>&1 || exit 1;\n")
            append("    touch ~/.pocket-agent-ready\n")
            append("    echo \"[init] Done — ready for future starts\";\n")
            append("  else\n")
            append("    echo \"[start] Environment ready, launching uvicorn...\";\n")
            append("    cd ~/$POCKET_AGENT_DIR;\n")
            append("  fi &&\n")
            append("  echo \"[uvicorn] Starting...\";\n")
            append("  # 先杀旧进程再启动，避免重复\n")
            append("  pkill -f \"uvicorn app:app\" 2>/dev/null || true;\n")
            append("  nohup uvicorn app:app --host 0.0.0.0 --port 8000 >/dev/null 2>&1 &\n")
            append("  echo \"[ok] Uvicorn started PID=\$!\";\n")
            append("} >~/startup.log 2>&1")
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
            append("  PID=\$(pgrep -f \"uvicorn app:app\" 2>/dev/null);\n")
            append("  if [ -n \"\$PID\" ]; then\n")
            append("    kill \"\$PID\" 2>/dev/null;\n")
            append("    echo \"[ok] Uvicorn PID=\$PID stopped\";\n")
            append("  else\n")
            append("    echo \"[info] No uvicorn process found\";\n")
            append("  fi\n")
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
            false
        }
    }
}
