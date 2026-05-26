package com.pocketagent.app.core

import android.content.Context
import android.content.Intent
import android.util.Log

object TermuxLauncher {
    private const val TAG = "TermuxLauncher"
    private const val TERMUX_PACKAGE = "com.termux"
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
     * 启动 FastAPI 服务。代码存储在 Termux 私有目录 ~/Pocket-Agent，
     * 避免 sdcardfs 跨应用权限限制。
     *
     * 关键修复历史：
     * - v1: RUN_COMMAND_PATH 传脚本字符串 → Termux 期望文件路径，脚本不执行
     * - v2: 改 bash -c + ARGUMENTS → 脚本正确执行
     * - v3: exec → nohup &，让脚本正常结束但 uvicorn 在后台持续运行
     */
    fun launchFastAPI(context: Context, mirrorUrl: String = ""): Boolean {
        if (!isTermuxInstalled(context)) {
            Log.w(TAG, "Termux not installed")
            return false
        }

        val pipEnv = if (mirrorUrl.isNotBlank()) "PIP_INDEX_URL=$mirrorUrl " else ""

        // bash -c 执行脚本，每步通过 am broadcast 发回实时状态
        val broadcast = "am broadcast -n com.pocketagent.app/com.pocketagent.app.core.ScriptStatusReceiver " +
                "-a com.pocketagent.app.SCRIPT_STATUS --es msg"
        val script = buildString {
            append("cd && { ")
            append("$broadcast '正在克隆 Pocket-Agent 代码库...' >/dev/null 2>&1; ")
            append("if [ ! -d ~/$POCKET_AGENT_DIR/.git ]; then ")
            append("  git clone $GIT_REPO ~/$POCKET_AGENT_DIR || exit 1; ")
            append("else ")
            append("  cd ~/$POCKET_AGENT_DIR && git pull origin main || true; ")
            append("fi && ")
            append("cd ~/$POCKET_AGENT_DIR && ")
            append("$broadcast '正在安装 fastapi+uvicorn...' >/dev/null 2>&1; ")
            append("${pipEnv}pip install -q fastapi uvicorn 2>&1 || exit 1; ")
            append("$broadcast '正在安装 requirements.txt 依赖（可能需 2-3 分钟）...' >/dev/null 2>&1; ")
            append("${pipEnv}pip install -q -r requirements.txt 2>&1 || exit 1; ")
            append("$broadcast '正在启动 uvicorn 服务...' >/dev/null 2>&1; ")
            append("nohup uvicorn app:app --host 0.0.0.0 --port 8000 >/dev/null 2>&1 & ")
            append("$broadcast '服务已启动！' >/dev/null 2>&1; ")
            append("} >~/startup.log 2>&1")
        }

        val intent = Intent(TERMUX_RUN_COMMAND).apply {
            setClassName(TERMUX_PACKAGE, "$TERMUX_PACKAGE.RunCommandService")
            action = TERMUX_RUN_COMMAND
            // RUN_COMMAND_PATH 需要可执行文件路径，内联脚本用 bash -c
            putExtra("$TERMUX_PACKAGE.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("$TERMUX_PACKAGE.RUN_COMMAND_ARGUMENTS", arrayOf("-c", script))
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
