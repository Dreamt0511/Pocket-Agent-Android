package com.pocketagent.app.overlay

/**
 * Agent 执行过程流式桥接器
 *
 * 在 AgentDaemon / AgentExecutor 中集成:
 *
 *   StreamBridge.out("[step] 正在分析指令...")
 *   StreamBridge.out("[task] → 打开微信")
 *   StreamBridge.status("执行步骤 2/5")
 *   StreamBridge.signalOperation(true)   // 开始操控 → 自动最小化悬浮窗
 *   StreamBridge.signalOperation(false)  // 操控结束 → 恢复
 *
 * 所有输出自动同步到:
 *   1. 悬浮窗 (OverlayService)
 *   2. 终端屏幕 (TerminalScreen)
 *   3. 任务历史 (TaskHistory)
 */
object StreamBridge {

    // 输出目标接口
    private val targets = mutableListOf<StreamTarget>()

    fun register(target: StreamTarget) {
        if (!targets.contains(target)) {
            targets.add(target)
        }
    }

    fun unregister(target: StreamTarget) {
        targets.remove(target)
    }

    // ─── 输出方法 ───────────────────────────

    /** 普通日志输出（自动追加换行） */
    fun out(message: String) {
        val line = if (message.endsWith("\n")) message else "$message\n"
        targets.forEach { it.onOutput(line) }
    }

    /** 流式 token 输出（不追加换行，保证连续文本） */
    fun stream(text: String) {
        targets.forEach { it.onOutput(text) }
    }

    /** 步骤输出 - 自动添加 [step] 前缀 */
    fun step(stepNum: Int, description: String) {
        out("[步骤 $stepNum] $description\n")
    }

    /** 任务描述输出 */
    fun task(description: String) {
        out("[task] $description\n")
    }

    /** 信息输出 */
    fun info(message: String) {
        out("[info] $message\n")
    }

    /** 错误输出 */
    fun error(message: String) {
        out("[error] $message\n")
    }

    /** 成功完成输出 */
    fun done(message: String) {
        out("[done] $message\n")
    }

    /** 更新状态文本 */
    fun status(status: String) {
        targets.forEach { it.onStatusChange(status) }
    }

    /** 标记 Agent 正在操控手机 */
    fun signalOperation(active: Boolean) {
        targets.forEach { it.onAgentOperation(active) }
    }

    // ─── 清理 ───────────────────────────────

    fun clear() {
        targets.clear()
    }

    // ─── 内置目标: 悬浮窗 (默认启用) ─────────

    init {
        register(object : StreamTarget {
            override fun onOutput(line: String) {
                OverlayService.streamText.value += line
            }

            override fun onStatusChange(status: String) {
                OverlayService.taskStatus.value = status
            }

            override fun onAgentOperation(active: Boolean) {
                OverlayService.isAgentOperating.value = active
            }
        })
    }
}

/**
 * 流式输出目标接口
 */
interface StreamTarget {
    fun onOutput(line: String) = Unit
    fun onStatusChange(status: String) = Unit
    fun onAgentOperation(active: Boolean) = Unit
}