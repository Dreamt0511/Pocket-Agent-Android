package com.pocketagent.app.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 接收 Termux 脚本通过 am broadcast 发来的实时状态。
 * 脚本每完成一步就发一条广播，UI 层收集 ScriptProgress.status 即可显示。
 */
class ScriptStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val msg = intent.getStringExtra(EXTRA_MSG) ?: return
        ScriptProgress.update(msg)
    }

    companion object {
        const val ACTION = "com.pocketagent.app.SCRIPT_STATUS"
        const val EXTRA_MSG = "msg"
    }
}

object ScriptProgress {
    val status: MutableStateFlow<String?> = MutableStateFlow(null)

    fun update(msg: String) {
        status.value = msg
    }

    fun reset() {
        status.value = null
    }
}
