package com.pocketagent.app.backscreen

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import com.pocketagent.app.overlay.StreamBridge
import com.pocketagent.app.overlay.StreamTarget

/**
 * 背屏管理器（单例）
 *
 * 管理背屏（Display ID 1）上 Presentation 的生命周期，
 * 并接入 StreamBridge 接收 Agent 实时输出。
 */
object BackScreenManager {

    private var presentation: BackScreenPresentation? = null
    private var displayManager: DisplayManager? = null
    private var _enabled = false
    val isEnabled: Boolean get() = _enabled

    fun init(context: Context) {
        displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    /** 背屏是否可用（硬件存在） */
    fun isAvailable(): Boolean {
        return displayManager?.getDisplay(1) != null
    }

    /** 获取背屏 Display 对象 */
    fun getBackDisplay(): Display? = displayManager?.getDisplay(1)

    /** 启用背屏：显示 Presentation + 注册到 StreamBridge */
    fun enable(context: Context) {
        if (_enabled) return
        val display = displayManager?.getDisplay(1) ?: return

        presentation = BackScreenPresentation(context.applicationContext, display).apply {
            show()
        }
        _enabled = true
        StreamBridge.register(streamTarget)
        StreamBridge.out("[info] 背屏终端已启动")
    }

    /** 禁用背屏：关闭 Presentation + 从 StreamBridge 移除 */
    fun disable() {
        if (!_enabled) return
        StreamBridge.unregister(streamTarget)
        presentation?.dismiss()
        presentation = null
        _enabled = false
    }

    /** 切换开关 */
    fun toggle(context: Context): Boolean {
        if (_enabled) disable() else enable(context)
        return _enabled
    }

    /** 清空背屏显示 */
    fun clear() {
        presentation?.clearText()
    }

    // ── StreamBridge 输出目标 ──

    private val streamTarget = object : StreamTarget {
        override fun onOutput(line: String) {
            presentation?.appendText(line)
        }
    }
}
