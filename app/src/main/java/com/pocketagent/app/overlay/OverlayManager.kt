package com.pocketagent.app.overlay

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.net.Uri
import android.os.Build

/**
 * 悬浮窗全局管理器 (单例)
 *
 * 对外统一接口，让 Agent 服务在 App 退后台时
 * 自动启动悬浮窗并流式输出执行过程。
 *
 * 使用方法:
 *   OverlayManager.with(context).showMini()
 *   OverlayManager.with(context).appendStream("[step] 正在打开微信\n")
 *   OverlayManager.with(context).signalAgentOperating(true)
 */
object OverlayManager {

    private var appContext: Context? = null
    private var isServiceRunning = false

    /**
     * 初始化 - 建议在 Application.onCreate() 中调用
     */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * 检查悬浮窗权限是否已授予
     */
    fun hasOverlayPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    /**
     * 打开系统悬浮窗权限设置页
     */
    fun openOverlayPermissionSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    // ─── 悬浮窗控制 ──────────────────────────

    /**
     * 显示迷你悬浮窗
     */
    fun showMini() {
        startService(OverlayService.ACTION_SHOW)
    }

    /**
     * 显示展开悬浮窗 (带终端输出)
     */
    fun showExpanded() {
        startService(OverlayService.ACTION_EXPAND)
    }

    /**
     * 折叠为迷你模式
     */
    fun minimize() {
        startService(OverlayService.ACTION_MINIMIZE)
    }

    /**
     * 完全隐藏悬浮窗
     */
    fun hide() {
        startService(OverlayService.ACTION_HIDE)
    }

    /**
     * 追加流式输出文本 (线程安全)
     *
     * Agent 执行过程中每产生一条输出时调用:
     *   OverlayManager.with(context).appendStream("[step] 打开微信 → 成功\n")
     */
    fun appendStream(text: String) {
        OverlayService.streamText.value += text
    }

    /**
     * 清空并重新设置流式输出
     */
    fun setStreamText(text: String) {
        OverlayService.streamText.value = text
    }

    /**
     * 更新任务状态
     */
    fun updateStatus(status: String) {
        OverlayService.taskStatus.value = status
    }

    /**
     * 标记 Agent 正在操控手机（触发悬浮窗自动最小化）
     */
    fun signalAgentOperating(operating: Boolean) {
        OverlayService.isAgentOperating.value = operating
    }

    // ─── 内部 ──────────────────────────────

    private fun startService(action: String) {
        val ctx = appContext ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(ctx)) {
            openOverlayPermissionSettings(ctx)
            return
        }

        val intent = Intent(ctx, OverlayService::class.java).apply {
            this.action = action
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }
    }
}