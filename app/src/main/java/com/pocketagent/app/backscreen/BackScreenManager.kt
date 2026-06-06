package com.pocketagent.app.backscreen

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.WindowManager
import com.pocketagent.app.overlay.StreamBridge
import com.pocketagent.app.overlay.StreamTarget

/**
 * 背屏管理器（单例）
 *
 * 管理背屏（Display ID 1）上 Presentation 的生命周期，
 * 并接入 StreamBridge 接收 Agent 实时输出。
 */
object BackScreenManager {

    private const val TAG = "BackScreenManager"
    private const val BACK_DISPLAY_ID = 1

    private var presentation: BackScreenPresentation? = null
    private var displayManager: DisplayManager? = null
    private var _enabled = false
    val isEnabled: Boolean get() = _enabled

    fun init(context: Context) {
        displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    /** 背屏是否可用（硬件存在） */
    fun isAvailable(): Boolean {
        return displayManager?.getDisplay(BACK_DISPLAY_ID) != null
    }

    /** 获取背屏 Display 对象 */
    fun getBackDisplay(): Display? = displayManager?.getDisplay(BACK_DISPLAY_ID)

    /** 启用背屏：显示 Presentation + 注册到 StreamBridge
     *  @return true=成功, false=失败
     */
    fun enable(context: Context): Boolean {
        if (_enabled) return true
        val display = displayManager?.getDisplay(BACK_DISPLAY_ID) ?: return false

        try {
            // 必须用 Activity 上下文（带窗口 Token），不能用 applicationContext
            presentation = BackScreenPresentation(context, display).apply {
                show()
            }
            _enabled = true
            StreamBridge.register(streamTarget)
            StreamBridge.out("[info] 背屏终端已启动")
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
