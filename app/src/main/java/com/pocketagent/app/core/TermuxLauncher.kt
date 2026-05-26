package com.pocketagent.app.core

import android.content.Context
import android.content.Intent
import android.util.Log

object TermuxLauncher {
    private const val TAG = "TermuxLauncher"
    private const val TERMUX_PACKAGE = "com.termux"
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
     * 启动 FastAPI 服务。由 Termux 执行所有文件操作（git clone/pip install/uvicorn）。
     * Termux 通过 termux-setup-storage 拥有 /sdcard/ 写权限。
     */
    fun launchFastAPI(context: Context, mirrorUrl: String = ""): Boolean {
        if (!isTermuxInstalled(context)) {
            Log.w(TAG, "Termux not installed")
            return false
        }

        val pipEnv = if (mirrorUrl.isNotBlank()) "PIP_INDEX_URL=$mirrorUrl " else ""

        // Termux 脚本：git clone(首次) 或 git pull(更新) + pip install + 启动 uvicorn
        val script = buildString {
            append("if [ ! -d $POCKET_AGENT_DIR/.git ]; then")
            append(" mkdir -p $POCKET_AGENT_DIR")
            append(" && git clone $GIT_REPO $POCKET_AGENT_DIR")
            append("; else")
            append(" cd $POCKET_AGENT_DIR && git pull origin main")
            append("; fi")
            append(" && cd $POCKET_AGENT_DIR")
            append(" && ${pipEnv}pip install fastapi uvicorn -q")
            append(" && ${pipEnv}pip install -r requirements.txt -q")
            append(" && exec uvicorn app:app --host 0.0.0.0 --port 8000")
        }

        val intent = Intent(TERMUX_RUN_COMMAND).apply {
            setClassName(TERMUX_PACKAGE, "$TERMUX_PACKAGE.RunCommandService")
            action = TERMUX_RUN_COMMAND
            putExtra("$TERMUX_PACKAGE.RUN_COMMAND_PATH", script)
            putExtra("$TERMUX_PACKAGE.RUN_COMMAND_WORKDIR", POCKET_AGENT_DIR)
            putExtra("$TERMUX_PACKAGE.RUN_COMMAND_BACKGROUND", true)
        }

        return try {
            context.startService(intent)
            Log.i(TAG, "Termux FastAPI launch intent sent")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Termux", e)
            false
        }
    }
}
