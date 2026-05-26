package com.pocketagent.app.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*
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

/**
 * 全局启动状态持有者。使用独立的 CoroutineScope，不依赖任何页面生命周期。
 * 用户切页面再回来，进度不会丢失，5 分钟超时也不会中断。
 */
object ScriptProgress {
    val status: MutableStateFlow<String?> = MutableStateFlow(null)
    val isLaunching: MutableStateFlow<Boolean> = MutableStateFlow(false)

    // 全局作用域，随进程存活，不依附于任何 Activity/Composable
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 启动监听 — 接收广播更新 + 收到"服务已启动！"后做一次 health 确认 + 超时保护 */
    fun startLaunch() {
        status.value = "已发送启动指令，等待 Termux 脚本上报进度..."
        isLaunching.value = true

        scope.launch {
            status.collect { msg ->
                if (msg == "服务已启动！" && isLaunching.value) {
                    val result = withContext(Dispatchers.IO) {
                        TermuxServiceClient.healthCheck()
                    }
                    status.value = when (result) {
                        is TermuxServiceClient.HealthResult.Ok -> "服务已就绪!"
                        else -> "$msg（但 HTTP 连接暂未就绪，稍后点「测试连接」确认）"
                    }
                    isLaunching.value = false
                }
            }
        }

        // 5 分钟超时保护
        scope.launch {
            delay(300_000L)
            if (isLaunching.value) {
                status.value = "启动超时（5 分钟），请检查 Termux 的 ~/startup.log"
                isLaunching.value = false
            }
        }
    }

    fun update(msg: String) {
        status.value = msg
    }

    fun reset() {
        status.value = null
        isLaunching.value = false
    }
}
