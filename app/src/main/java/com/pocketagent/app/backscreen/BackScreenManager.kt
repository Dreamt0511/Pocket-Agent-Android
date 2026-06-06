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
 * 管理背屏上 Presentation 的生命周期，
 * 并接入 StreamBridge 接收 Agent 实时输出。
 */
object BackScreenManager {

    private const val TAG = "BackScreenManager"

    private var presentation: BackScreenPresentation? = null
    private var displayManager: DisplayManager? = null
    private var contextRef: Context? = null
    private var _enabled = false
    val isEnabled: Boolean get() = _enabled

    fun init(context: Context) {
        contextRef = context
        displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    /** 穷举所有可用 Display，找背屏 */
    private fun findBackDisplay(): Display? {
        val dm = displayManager ?: return null

        // 输出所有可用 Display 方便排查
        val all = dm.getDisplays()
        Log.d(TAG, "getDisplays() 总数: ${all.size}")
        for (d in all) {
            Log.d(TAG, "  displayId=${d.displayId} flags=0x${d.flags.toString(16)} name=${d.name}")
        }

        // 方法1: 遍历全部，只要不是主屏就返回
        for (d in all) {
            if (d.displayId != Display.DEFAULT_DISPLAY) {
                Log.i(TAG, "方法1 找到副屏: displayId=${d.displayId}")
                return d
            }
        }

        // 方法2: getDisplay(1)（小米背屏通常是 1）
        dm.getDisplay(1)?.let {
            Log.i(TAG, "方法2 找到: displayId=1 flags=0x${it.flags.toString(16)}")
            return it
        }

        // 方法3: MediaRouter 探查 presentation display
        try {
            val ctx = contextRef ?: return null
            val mr = ctx.getSystemService(Context.MEDIA_ROUTER_SERVICE) as MediaRouter
            for (i in 0 until mr.routeCount) {
                val route = mr.getRouteAt(i)
                val pd = route.presentationDisplay
                if (pd != null && pd.displayId != Display.DEFAULT_DISPLAY) {
                    Log.i(TAG, "方法3 MediaRouter 找到: displayId=${pd.displayId}")
                    return pd
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "方法3 失败", e)
        }

        // 方法4: 反射 DisplayManagerGlobal.getRealDisplay(1)
        try {
            val clazz = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val getInstance = clazz.getDeclaredMethod("getInstance")
            val dmg = getInstance.invoke(null)
            val getRealDisplay = clazz.getDeclaredMethod("getRealDisplay", Int::class.javaPrimitiveType ?: Int::class.java)
            val display = getRealDisplay.invoke(dmg, 1) as? Display
            if (display != null) {
                Log.i(TAG, "方法4 反射找到: displayId=${display.displayId}")
                return display
            }
        } catch (e: Exception) {
            Log.w(TAG, "方法4 反射失败", e)
        }

        Log.w(TAG, "所有方法均未检测到背屏")
        return null
    }

    /** 启用背屏：显示 Presentation + 注册到 StreamBridge
     *  @return true=成功, false=失败
     */
    fun enable(context: Context): Boolean {
        if (_enabled) return true
        val display = findBackDisplay() ?: return false

        try {
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
