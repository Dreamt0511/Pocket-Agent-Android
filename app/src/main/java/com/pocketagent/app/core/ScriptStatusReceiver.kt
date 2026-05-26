package com.pocketagent.app.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 接收 Termux 脚本通过 am broadcast 发来的实时状态（可能因 SELinux/proot 被屏蔽）。
 */
class ScriptStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val msg = intent.getStringExtra(EXTRA_MSG) ?: return
        ScriptProgress.onBroadcast(msg)
    }

    companion object {
        const val ACTION = "com.pocketagent.app.SCRIPT_STATUS"
        const val EXTRA_MSG = "msg"
    }
}

/**
 * 全局启动状态。独立 CoroutineScope，不依赖页面生命周期。
 * 切页面再回来进度不丢失、超时不中断。
 */
object ScriptProgress {
    val status: MutableStateFlow<String?> = MutableStateFlow(null)
    val isLaunching: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val permPrompted: MutableStateFlow<Boolean> = MutableStateFlow(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 启动服务：开始 waitForService 轮询，显示真实尝试次数和等待时间 */
    fun startLaunch() {
        status.value = "已发送启动指令，正在检测服务..."
        isLaunching.value = true

        scope.launch {
            val r = TermuxServiceClient.waitForService(
                maxAttempts = 60,
                intervalMs = 5000L,
                onAttempt = { attempt, total, error, sec ->
                    if (!isLaunching.value) return@onAttempt
                    status.value = if (error.isEmpty()) {
                        "第 ${attempt}/${total} 次尝试（已等待 ${sec} 秒）"
                    } else {
                        "第 ${attempt}/${total} 次（${sec} 秒）上次错误: $error"
                    }
                }
            )
            if (isLaunching.value) {
                status.value = if (r.success)
                    "服务已就绪!"
                else
                    "启动超时（检查 Termux ~/startup.log）: ${r.message}"
                isLaunching.value = false
            }
        }
    }

    /** 广播回调，仅用作辅助（可能收不到） */
    fun onBroadcast(msg: String) {
        status.value = msg
        if (msg == "服务已启动！") {
            scope.launch {
                val r = withContext(Dispatchers.IO) { TermuxServiceClient.healthCheck() }
                status.value = if (r is TermuxServiceClient.HealthResult.Ok)
                    "服务已就绪!" else "服务已启动（HTTP 暂未就绪）"
                isLaunching.value = false
            }
        }
    }

    fun reset() {
        status.value = null
        isLaunching.value = false
    }
}
