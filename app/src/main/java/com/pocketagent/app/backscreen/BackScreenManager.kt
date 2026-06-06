package com.pocketagent.app.backscreen

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaRouter
import android.util.Log
import android.view.Display
import com.pocketagent.app.overlay.StreamBridge
import com.pocketagent.app.overlay.StreamTarget

/**
 * 背屏管理器（单例）
 *
 * 管理背屏上渲染视图的生命周期，
 * 并接入 StreamBridge 接收 Agent 实时输出。
 */
object BackScreenManager {

    private const val TAG = "BackScreenManager"

    private var presentation: BackScreenPresentation? = null
    private var displayManager: DisplayManager? = null
    private var _enabled = false
    val isEnabled: Boolean get() = _enabled

    fun init(context: Context) {
        displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    /** 穷举所有可用 Display，找背屏 */
    private fun findBackDisplay(): Display? {
        val dm = displayManager ?: return null

        Log.d(TAG, "===== 背屏检测开始 =====")

        // 方法0: DISPLAY_CATEGORY_PRESENTATION
        try {
            val presDisplays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            Log.d(TAG, "PRESENTATION 类别: ${presDisplays.size} 个")
            for (d in presDisplays) {
                Log.d(TAG, "  id=${d.displayId} flags=0x${d.flags.toString(16)} name=${d.name}")
                if (d.displayId != Display.DEFAULT_DISPLAY) {
                    Log.i(TAG, "方法0 找到: displayId=${d.displayId}")
                    return d
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "方法0 异常", e)
        }

        // 方法1: getDisplays() 全部枚举
        val all = dm.getDisplays()
        Log.d(TAG, "getDisplays() 总数: ${all.size}")
        for (d in all) {
            Log.d(TAG, "  id=${d.displayId} flags=0x${d.flags.toString(16)} name=${d.name}")
        }
        for (d in all) {
            if (d.displayId != Display.DEFAULT_DISPLAY) {
                Log.i(TAG, "方法1 找到: displayId=${d.displayId}")
                return d
            }
        }

        // 方法2: getDisplay(1)
        dm.getDisplay(1)?.let {
            Log.i(TAG, "方法2 找到: id=${it.displayId}")
            return it
        }

        // 方法3: MediaRouter
        try {
            val ctx = displayManager?.let { dm2 -> null } // 需要 context，跳过
        } catch (_: Exception) {}

        // 方法4: 反射 DisplayManagerGlobal.getAllDisplays()
        try {
            val clazz = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val getInstance = clazz.getDeclaredMethod("getInstance")
            val dmg = getInstance.invoke(null)
            val getAllDisplays = clazz.getDeclaredMethod("getAllDisplays")
            val allDisplays = getAllDisplays.invoke(dmg) as? Array<*>
            if (allDisplays != null) {
                Log.d(TAG, "反射 getAllDisplays: ${allDisplays.size} 个")
                for (d in allDisplays) {
                    val disp = d as? Display
                    Log.d(TAG, "  id=${disp?.displayId}")
                    if (disp != null && disp.displayId != Display.DEFAULT_DISPLAY) {
                        Log.i(TAG, "方法4 反射找到: displayId=${disp.displayId}")
                        return disp
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "方法4 反射失败", e)
        }

        Log.w(TAG, "===== 所有方法均未检测到背屏 =====")
        return null
    }

    /** 启用背屏：显示视图 + 注册到 StreamBridge */
    fun enable(context: Context): Boolean {
        if (_enabled) return true
        val display = findBackDisplay() ?: run {
            Log.e(TAG, "enable() 失败: 未检测到背屏")
            return false
        }

        try {
            presentation = BackScreenPresentation(context, display)
            _enabled = true
            StreamBridge.register(streamTarget)
            StreamBridge.out("[info] 背屏显示已启动")
            Log.i(TAG, "enable() 成功, displayId=${display.displayId}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "背屏启动失败", e)
            presentation?.dismiss()
            presentation = null
            _enabled = false
            return false
        }
    }

    /** 禁用背屏：关闭视图 + 从 StreamBridge 移除 */
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
