package com.pocketagent.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.*
import com.pocketagent.app.core.TermuxLauncher
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow

/**
 * 全局悬浮窗服务 - 退到后台后仍悬浮在所有应用上层
 *
 * 双模式:
 * - MINI:   ~160dp 药丸形迷你条，图标 + 流式文本滚动 + 状态点
 * - EXPANDED: 半透明终端卡片，实时流式输出，可拖拽缩放
 *
 * 无障碍协作:
 * - Agent 操作时 EXPANDED → MINI 并移角落
 * - MINI 模式下加 FLAG_NOT_TOUCHABLE 完全不干扰 UI 树获取
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_SHOW = "com.pocketagent.app.overlay.SHOW"
        const val ACTION_HIDE = "com.pocketagent.app.overlay.HIDE"
        const val ACTION_EXPAND = "com.pocketagent.app.overlay.EXPAND"
        const val ACTION_MINIMIZE = "com.pocketagent.app.overlay.MINIMIZE"

        // 全局状态流 - 供外部写入
        val overlayState = kotlinx.coroutines.flow.MutableStateFlow(OverlayMode.HIDDEN)
        val streamText = kotlinx.coroutines.flow.MutableStateFlow("")
        val taskStatus = kotlinx.coroutines.flow.MutableStateFlow("空闲")
        val isAgentOperating = kotlinx.coroutines.flow.MutableStateFlow(false)

        /** 当前会话消息列表 — 供悬浮窗显示 */
        val conversationMessages = kotlinx.coroutines.flow.MutableStateFlow<List<OverlayMessage>>(emptyList())

        /** 显示迷你悬浮窗药丸 */
        fun showMini(context: Context) {
            try {
                context.startService(Intent(context, OverlayService::class.java).apply {
                    action = ACTION_SHOW
                })
            } catch (_: Exception) {}
        }

        /** 隐藏所有悬浮窗 */
        fun hideAll(context: Context) {
            try {
                context.startService(Intent(context, OverlayService::class.java).apply {
                    action = ACTION_HIDE
                })
            } catch (_: Exception) {}
        }
    }

    /** 悬浮窗显示的消息条目 */
    data class OverlayMessage(val text: String, val isUser: Boolean)

    enum class OverlayMode { HIDDEN, MINI, EXPANDED }

    private lateinit var windowManager: WindowManager
    private lateinit var miniView: MiniOverlayView
    private lateinit var expandedView: ExpandedOverlayView

    private var miniParams: WindowManager.LayoutParams? = null
    private var expandedParams: WindowManager.LayoutParams? = null
    private var currentMode = OverlayMode.HIDDEN

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 屏幕尺寸
    private var screenWidth = 0
    private var screenHeight = 0

    /** mini 模式基础 flags（不含 NOT_TOUCHABLE） */
    private val miniBaseFlags =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    override fun onCreate() {
        super.onCreate()

        // 前台服务通知
        val channelId = "overlay_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "悬浮窗服务", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("Pocket Agent")
                .setContentText("悬浮窗运行中")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Pocket Agent")
                .setContentText("悬浮窗运行中")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
        }
        startForeground(1001, notification)

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 获取屏幕尺寸
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            screenWidth = metrics.bounds.width()
            screenHeight = metrics.bounds.height()
        } else {
            val display = windowManager.defaultDisplay
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }

        // 一次性启动所有观察者（避免 onStartCommand 重复创建）
        observeTaskStatus()
        observeStreamText()
        observeAgentOperation()
        observeConversationMessages()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showMini()
            ACTION_HIDE -> hideAll()
            ACTION_EXPAND -> showExpanded()
            ACTION_MINIMIZE -> showMini()
            else -> showMini()
        }

        overlayState.value = currentMode
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── 观察者（在 onCreate 中一次性启动）────────────────

    /** 监听流式文本 → 同步到当前模式的视图 */
    private fun observeStreamText() {
        serviceScope.launch {
            streamText.collect { text ->
                when (currentMode) {
                    OverlayMode.MINI -> {
                        if (::miniView.isInitialized) miniView.setStreamText(text)
                    }
                    OverlayMode.EXPANDED -> {
                        if (::expandedView.isInitialized) {
                            if (text.isEmpty()) {
                                expandedView.clearStream()
                            } else {
                                expandedView.appendStream(text)
                            }
                        }
                    }
                    OverlayMode.HIDDEN -> {}
                }
            }
        }
    }

    /** 监听任务状态 → 同步到当前模式的视图 */
    private fun observeTaskStatus() {
        serviceScope.launch {
            taskStatus.collect { status ->
                when (currentMode) {
                    OverlayMode.MINI -> {
                        if (::miniView.isInitialized)
                            miniView.updateStatus(status, isAgentOperating.value)
                    }
                    OverlayMode.EXPANDED -> {
                        if (::expandedView.isInitialized) expandedView.updateStatus(status)
                    }
                    OverlayMode.HIDDEN -> {}
                }
            }
        }
    }

    /** 监听无障碍操作信号 → 自动缩小 + 设置触控穿透 */
    private fun observeAgentOperation() {
        serviceScope.launch {
            isAgentOperating.collect { operating ->
                if (operating && currentMode == OverlayMode.EXPANDED) {
                    showMini()
                    moveMiniToCorner()
                }

                // MINI 模式下：操作中 → FLAG_NOT_TOUCHABLE 触控穿透
                if (currentMode == OverlayMode.MINI && ::miniView.isInitialized && miniParams != null) {
                    if (operating) {
                        miniParams!!.flags = miniBaseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    } else {
                        miniParams!!.flags = miniBaseFlags
                    }
                    try {
                        windowManager.updateViewLayout(miniView, miniParams)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    /** 监听会话消息 → 同步到展开视图 */
    private fun observeConversationMessages() {
        serviceScope.launch {
            conversationMessages.collect { messages ->
                if (currentMode == OverlayMode.EXPANDED && ::expandedView.isInitialized) {
                    expandedView.setMessages(messages)
                }
            }
        }
    }

    // ─── 迷你悬浮窗 ─────────────────────────────────

    private fun showMini() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return

        // 保存展开窗状态，下次展开时恢复
        expandedParams?.let { params ->
            getSharedPreferences("overlay_prefs", MODE_PRIVATE).edit()
                .putInt("expanded_width", params.width)
                .putInt("expanded_height", params.height)
                .putFloat("font_size", if (::expandedView.isInitialized) expandedView.getFontSizeSp() else 11f)
                .apply()
            if (::expandedView.isInitialized) try { windowManager.removeView(expandedView) } catch (_: Exception) {}
        }
        expandedParams = null

        if (miniParams == null) {
            miniView = MiniOverlayView(
                context = this,
                onToggleExpand = { showExpanded() },
                onMoveToCorner = { moveMiniToCorner() }
            )

            miniParams = WindowManager.LayoutParams().apply {
                type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                flags = miniBaseFlags

                format = PixelFormat.TRANSLUCENT
                width = WindowManager.LayoutParams.WRAP_CONTENT
                height = WindowManager.LayoutParams.WRAP_CONTENT
                gravity = Gravity.TOP or Gravity.START
                x = screenWidth - dpToPx(60)
                y = screenHeight / 3
            }

            setupMiniDrag()
            windowManager.addView(miniView, miniParams)
        }

        // 同步当前状态
        miniView.updateStatus(taskStatus.value, isAgentOperating.value)
        miniView.setStreamText(streamText.value)

        currentMode = OverlayMode.MINI
        overlayState.value = OverlayMode.MINI

        // 如果 agent 正在操作，立即设置触控穿透
        //（防止先于 agent 操作状态变化显示 mini 时状态流不重复发射）
        if (isAgentOperating.value && miniParams != null) {
            miniParams!!.flags = miniBaseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            try { windowManager.updateViewLayout(miniView, miniParams) } catch (_: Exception) {}
        }
    }

    private var isMiniHalfHidden = false

    private fun setupMiniDrag() {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        val dragThreshold = 10f

        miniView.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    // 半隐藏状态下点击，先拉出来
                    if (isMiniHalfHidden) {
                        isMiniHalfHidden = false
                        snapMiniToEdge(view, fullyVisible = true)
                        return@setOnTouchListener true
                    }
                    initialX = miniParams!!.x
                    initialY = miniParams!!.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - initialTouchX
                    val deltaY = event.rawY - initialTouchY

                    if (kotlin.math.abs(deltaX) > dragThreshold ||
                        kotlin.math.abs(deltaY) > dragThreshold) {
                        isDragging = true
                    }

                    if (isDragging) {
                        miniParams!!.x = initialX + deltaX.toInt()
                        miniParams!!.y = initialY + deltaY.toInt()

                        val viewW = view.width.coerceAtLeast(dpToPx(100))
                        val viewH = view.height.coerceAtLeast(dpToPx(40))
                        miniParams!!.x = miniParams!!.x.coerceIn(0, screenWidth - viewW)
                        miniParams!!.y = miniParams!!.y.coerceIn(0, screenHeight - viewH - dpToPx(80))

                        windowManager.updateViewLayout(miniView, miniParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        miniView.performClick()
                    } else {
                        // 松手后自动吸附到最近的屏幕边缘，半隐藏
                        snapMiniToEdge(view, fullyVisible = false)
                    }
                    true
                }
                else -> false
            }
        }
    }

    /** 将药丸吸附到最近的屏幕边缘 */
    private fun snapMiniToEdge(view: View, fullyVisible: Boolean) {
        miniParams?.let { params ->
            // 用固定最小宽度计算，避免 view 宽度因内容变化导致隐藏量不一致
            val viewW = dpToPx(100)
            val centerX = params.x + viewW / 2
            val hiddenOffset = viewW / 3

            params.x = if (centerX < screenWidth / 2) {
                // 左侧
                if (fullyVisible) 0 else -hiddenOffset
            } else {
                // 右侧
                if (fullyVisible) screenWidth - viewW else screenWidth - viewW + hiddenOffset
            }
            isMiniHalfHidden = !fullyVisible
            try { windowManager.updateViewLayout(miniView, params) } catch (_: Exception) {}
        }
    }

    fun moveMiniToCorner() {
        miniParams?.let { params ->
            snapMiniToEdge(miniView, fullyVisible = false)
        }
    }

    // ─── 展开悬浮窗 ─────────────────────────────────

    private fun showExpanded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return

        miniParams?.let { try { windowManager.removeView(miniView) } catch (_: Exception) {} }
        miniParams = null

        // 缩小前保存当前展开状态
        val savedFontSize = if (::expandedView.isInitialized) expandedView.getFontSizeSp() else 0f

        if (expandedParams == null) {
            val prefs = getSharedPreferences("overlay_prefs", MODE_PRIVATE)
            val defaultW = (screenWidth * 0.9).toInt()
            val defaultH = (screenHeight * 0.4).toInt()
            val savedW = prefs.getInt("expanded_width", defaultW)
            val savedH = prefs.getInt("expanded_height", defaultH)
            val savedFs = if (savedFontSize > 0f) savedFontSize else prefs.getFloat("font_size", 11f)

            expandedView = ExpandedOverlayView(
                context = this,
                onMinimize = { showMini() },
                onClose = { hideAll() },
                onResize = { newW, newH ->
                    expandedParams?.let { params ->
                        params.width = newW.coerceAtLeast(dpToPx(200))
                        params.height = newH.coerceAtLeast(dpToPx(150))
                        try { windowManager.updateViewLayout(expandedView, params) } catch (_: Exception) {}
                    }
                },
                onDrag = { dx, dy ->
                    expandedParams?.let { params ->
                        params.x += dx
                        params.y += dy
                        try { windowManager.updateViewLayout(expandedView, params) } catch (_: Exception) {}
                    }
                },
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                initialFontSize = savedFs
            )

            expandedParams = WindowManager.LayoutParams().apply {
                type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

                format = PixelFormat.TRANSLUCENT
                width = savedW
                height = savedH
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dpToPx(60)
            }

            windowManager.addView(expandedView, expandedParams)
        }

        // 同步当前状态（先清空 buffer 再重填，避免 diff 计算错误）
        expandedView.updateStatus(taskStatus.value)
        expandedView.clearStream()
        // 优先显示完整会话消息，否则显示流式文本
        val msgs = conversationMessages.value
        if (msgs.isNotEmpty()) {
            expandedView.setMessages(msgs)
        } else if (streamText.value.isNotEmpty()) {
            expandedView.appendStream(streamText.value)
        }

        currentMode = OverlayMode.EXPANDED
        overlayState.value = OverlayMode.EXPANDED
    }

    // ─── 公开 API ────────────────────────────────────

    fun hideAll() {
        try { stopForeground(true) } catch (_: Exception) {}
        miniParams?.let { try { windowManager.removeView(miniView) } catch (_: Exception) {} }
        expandedParams?.let { if (::expandedView.isInitialized) try { windowManager.removeView(expandedView) } catch (_: Exception) {} }
        miniParams = null
        expandedParams = null
        currentMode = OverlayMode.HIDDEN
        overlayState.value = OverlayMode.HIDDEN
        try { stopSelf() } catch (_: Exception) {}
    }

    fun appendStreamText(text: String) {
        streamText.value += text
    }

    fun updateTaskStatus(status: String) {
        taskStatus.value = status
    }

    // ─── 工具方法 ────────────────────────────────────

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        try { stopForeground(true) } catch (_: Exception) {}
        hideAll()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        try { stopForeground(true) } catch (_: Exception) {}
        hideAll()
        // 通过 Termux Intent 杀掉 uvicorn（比 HTTP POST 可靠，Intent 投递到 Termux 独立进程）
        TermuxLauncher.stopFastAPI(this)
        super.onTaskRemoved(rootIntent)
    }
}
