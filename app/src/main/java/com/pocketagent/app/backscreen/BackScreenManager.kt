package com.pocketagent.app.backscreen

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import com.pocketagent.app.overlay.StreamBridge
import com.pocketagent.app.overlay.StreamTarget

/**
 * 背屏管理器（单例）
 *
 * 管理背屏上 Presentation 的生命周期，
 * 并接入 StreamBridge 接收 Agent 实时输出。
 */
object BackScreenManager {

    private const val TAG = "BackScreenManager"

    private var presentation: BackScreenPresentation? = null
    private var displayManager: DisplayManager? = null
    private var _enabled = false
    val isEnabled: Boolean get() = _enabled

    /** 缓存检测到的背屏 Display */
    private var _backDisplay: Display? = null

    fun init(context: Context) {
        displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        // 初始化时扫描背屏
        scanBackDisplay(context)
    }

    /** 扫描系统中所有可用的 Presentation Display */
    private fun scanBackDisplay(context: Context) {
        val dm = displayManager ?: return
        // 优先用 getDisplays 枚举所有 display
        for (display in dm.getDisplays()) {
            if ((display.flags and Display.FLAG_PRESENTATION) != 0) {
                _backDisplay = display
                Log.i(TAG, "发现背屏: displayId=${display.displayId} flags=${display.flags}")
                return
            }
        }
        // 降级：尝试直接拿 displayId=1（小米背屏通常是 1）
        _backDisplay = dm.getDisplay(1)
        if (_backDisplay != null) {
            Log.i(TAG, "通过 ID 1 找到背屏")
        } else {
            Log.w(TAG, "未检测到背屏")
        }
    }

    /** 背屏是否可用（硬件存在） */
    fun isAvailable(): Boolean = _backDisplay != null

    /** 启用背屏：显示 Presentation + 注册到 StreamBridge
     *  @return true=成功, false=失败
     */
    fun enable(context: Context): Boolean {
        if (_enabled) return true
        val display = _backDisplay ?: return false

        try {
            // 必须用 Activity 上下文（带窗口 Token），不能用 applicationContext
            presentation = BackScreenPresentation(context, display).apply {
                show()
            }
            _enabled = true
            StreamBridge.register(streamTarget)
            StreamBridge.out("[info] 背屏显示已启动")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "背屏启动失败", e)
            presentation?.dismiss()
            presentation = null
            _enabled = false
            return false
        }
    }

    /** 禁用背屏：关闭 Presentation + 从 StreamBridge 移除 */
    fun disable() {
        if (!_enabled) return
        try {
            StreamBridge.unregister(streamTarget)
            presentation?.dismiss()
        } catch (_: Exception) {
        }
        presentation = null
        _enabled = false
    }

    /** 切换开关 */
    fun toggle(context: Context): Boolean {
        return if (_enabled) { disable(); false }
        else enable(context)
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
