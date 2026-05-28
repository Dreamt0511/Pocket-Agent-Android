package com.pocketagent.app.core

import android.content.Context
import android.content.Intent
import android.util.Log

object TermuxLauncher {
    private const val TAG = "TermuxLauncher"
    private const val TERMUX_PACKAGE = "com.termux"
    private const val TERMUX_SERVICE = "$TERMUX_PACKAGE.app.RunCommandService"
    private const val TERMUX_RUN_COMMAND = "com.termux.RUN_COMMAND"
    private const val POCKET_AGENT_DIR = "/sdcard/Pocket-Agent"
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
     * 启动 FastAPI 服务。首次运行会 git clone + pip install，
     * 后续每次启动都会 pip install -r requirements.txt 确保依赖最新。
     */
    fun launchFastAPI(context: Context, mirrorUrl: String = ""): Boolean {
        if (!isTermuxInstalled(context)) {
            Log.w(TAG, "Termux not installed")
            return false
        }

        val pipEnv = if (mirrorUrl.isNotBlank()) "PIP_INDEX_URL=$mirrorUrl " else ""

        val script = buildString {
            append("{\n")
            append("  echo \"=== Pocket-Agent \$(date) ===\";\n")
            append("  mkdir -p ~/$POCKET_AGENT_DIR\n")
            append("  cd ~/$POCKET_AGENT_DIR || exit 1\n")
            // 首次运行：git clone + 完整安装
            append("  if [ ! -f ~/.pocket-agent-ready ]; then\n")
            append("    echo \"[init] First run — setting up environment...\";\n")
            append("    if [ ! -d .git ]; then\n")
            append("      git clone $GIT_REPO . || exit 1;\n")
            append("    fi\n")
            append("    echo \"[init] Installing fastapi+uvicorn...\";\n")
            append("    ${pipEnv}pip install -q fastapi uvicorn 2>&1 || exit 1;\n")
            append("    touch ~/.pocket-agent-ready\n")
            append("    echo \"[init] Base packages installed\";\n")
            append("  else\n")
            append("    echo \"[start] Environment ready, checking dependencies...\";\n")
            append("  fi\n")
            // 每次启动都安装 requirements.txt，确保新增依赖被装上
            append("  echo \"[deps] pip install -r requirements.txt...\";\n")
            append("  ${pipEnv}pip install -q -r requirements.txt 2>&1\n")
            append("  echo \"[uvicorn] Starting...\";\n")
            append("  for pid in \$(pgrep -f 'uvicorn.*app:app' 2>/dev/null); do [ \"\$pid\" != \"\$\$\" ] && kill \$pid 2>/dev/null; done\n")
            append("  sleep 1\n")
            append("  setsid nohup uvicorn app:app --host 0.0.0.0 --port 8000 </dev/null >~/uvicorn.log 2>&1 &\n")
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
            append("  for pid in \$(pgrep -f 'uvicorn.*app:app' 2>/dev/null); do\n")
            append("    [ \"\$pid\" != \"\$\$\" ] && kill \"\$pid\" 2>/dev/null && echo \"[ok] kill PID \$pid\"\n")
            append("  done\n")
            append("  sleep 1\n")
            append("  if pgrep -f 'uvicorn.*app:app' 2>/dev/null | grep -qv \"^\$\$\$\"; then\n")
            append("    echo \"[warn] uvicorn 可能还在运行\"\n")
            append("  else\n")
            append("    echo \"[ok] uvicorn 已停止\"\n")
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
