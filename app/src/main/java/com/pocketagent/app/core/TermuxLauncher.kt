package com.pocketagent.app.core

import android.content.Context
import android.content.Intent
import android.util.Log

object TermuxLauncher {
    private const val TAG = "TermuxLauncher"
    private const val TERMUX_PACKAGE = "com.termux"
    private const val TERMUX_RUN_COMMAND = "com.termux.RUN_COMMAND"
    private const val POCKET_AGENT_DIR = "/sdcard/Pocket-Agent"

    fun isTermuxInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun launchFastAPI(context: Context): Boolean {
        if (!isTermuxInstalled(context)) {
            Log.w(TAG, "Termux not installed")
            return false
        }

        // 启动命令：自动安装 fastapi/uvicorn + 项目依赖 + 启动服务
        val script = buildString {
            append("cd $POCKET_AGENT_DIR")
            append(" && pip install fastapi uvicorn -q")           // 基础依赖
            append(" && pip install -r requirements.txt -q")      // 项目依赖
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
