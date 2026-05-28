package com.pocketagent.app.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.widget.NestedScrollView
import kotlin.math.roundToInt

/**
 * 展开的悬浮窗视图 — 半透明终端风格，支持拖拽缩放
 *
 * 布局:
 * ┌──────────────────────────────────┐
 * │ ■ Pocket Agent     [－] [×]     │  ← 标题栏 (可拖拽移动)
 * ├──────────────────────────────────┤
 * │ $ 正在分析指令...               │
 * │   → 步骤 1: 打开微信            │  ← 滚动终端 (实时流式)
 * │   → 步骤 2: 搜索联系人          │
 * │   → 步骤 3: 发送消息            │
 * ├──────────────────────────────────┤
 * │ 空闲                        ╲  │  ← 状态栏 + 右下角缩放手柄
 * └──────────────────────────────────┘
 */
class ExpandedOverlayView(
    private val context: Context,
    private val onMinimize: () -> Unit,
    private val onClose: () -> Unit,
    private val onResize: (width: Int, height: Int) -> Unit,
    private val onDrag: (dx: Int, dy: Int) -> Unit,
    private val screenWidth: Int,
    private val screenHeight: Int
) : LinearLayout(context) {

    val headerView: View
    private lateinit var titleText: TextView
    private lateinit var terminalText: TextView
    private val scrollView: NestedScrollView
    private lateinit var statusText: TextView

    // 基础字号（缩放时按比例变化）
    private val baseTitleSize = 13f
    private val baseStatusSize = 10f

    private val terminalBuffer = StringBuilder()

    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private val dragThreshold = 10f

    // ── 字体大小（双指缩放） ──
    private var fontSizeSp = 11f
    private val minFontSize = 5f
    private val maxFontSize = 30f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = true

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = detector.scaleFactor
            fontSizeSp = (fontSizeSp * factor).coerceIn(minFontSize, maxFontSize)
            terminalText.textSize = fontSizeSp
            // 标题和状态栏按比例缩放
            val ratio = fontSizeSp / 11f // 11f 是终端默认字号
            titleText.textSize = (baseTitleSize * ratio).coerceIn(7f, 20f)
            statusText.textSize = (baseStatusSize * ratio).coerceIn(6f, 16f)
            return true
        }
    })

    // 缩放手柄拖拽状态
    private var resizeInitialW = 0
    private var resizeInitialH = 0
    private var resizeInitialRawX = 0f
    private var resizeInitialRawY = 0f

    // 双指缩放时拦截子视图的事件，让 ScaleGestureDetector 能收到多点触控
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.pointerCount >= 2) return true
        return super.onInterceptTouchEvent(ev)
    }

    // 只处理双指缩放，单指事件放行让子视图正常处理（滚动、按钮点击）
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        return scaleDetector.isInProgress
    }

    init {
        orientation = VERTICAL

        // 整体背景
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(Color.parseColor("#E61A1A2E"))
            setStroke(dp(0.5f).toInt(), Color.parseColor("#44FFFFFF"))
        }

        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        // ═══ 标题栏 ═══
        headerView = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(6), dp(4))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(
                    dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat(),
                    0f, 0f, 0f, 0f
                )
                setColor(Color.parseColor("#331A1A2E"))
            }

            titleText = TextView(context).apply {
                text = "Pocket Agent"
                setTextColor(Color.parseColor("#CCFFFFFF"))
                textSize = baseTitleSize
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }.also { tv ->
                // 拖拽窗口：仅在标题文字区域拖动，不干扰按钮点击
                tv.setOnTouchListener { _, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            dragStartRawX = event.rawX
                            dragStartRawY = event.rawY
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = (event.rawX - dragStartRawX).roundToInt()
                            val dy = (event.rawY - dragStartRawY).roundToInt()
                            if (dx != 0 || dy != 0) {
                                onDrag(dx, dy)
                                dragStartRawX = event.rawX
                                dragStartRawY = event.rawY
                            }
                            true
                        }
                        else -> true
                    }
                }
            }
            addView(titleText)

            val minimizeBtn = TextView(context).apply {
                text = "－"
                setTextColor(Color.parseColor("#AAFFFFFF"))
                textSize = 18f
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(2), dp(4), dp(2))
                setOnClickListener { onMinimize() }
            }
            addView(minimizeBtn)

            val closeBtn = TextView(context).apply {
                text = "×"
                setTextColor(Color.parseColor("#80FFFFFF"))
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(2), dp(8), dp(2))
                setOnClickListener { onClose() }
            }
            addView(closeBtn)
        }
        addView(headerView)

        // 分割线
        addView(View(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(0.5f)
            )
            setBackgroundColor(Color.parseColor("#22FFFFFF"))
        })

        // ═══ 终端内容 (可滚动) ═══
        scrollView = NestedScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            isFillViewport = true
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setBackgroundColor(Color.parseColor("#0D000000"))
        }

        terminalText = TextView(context).apply {
            setTextColor(Color.parseColor("#CCE0E0E0"))
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setLineSpacing(2f, 1f)
            text = "$ Pocket Agent 就绪\n"
        }
        scrollView.addView(terminalText)
        addView(scrollView)

        // 分割线
        addView(View(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(0.5f)
            )
            setBackgroundColor(Color.parseColor("#22FFFFFF"))
        })

        // ═══ 底部状态栏 + 缩放手柄 ═══
        val statusRow = LinearLayout(context).apply {
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(2), dp(2), dp(2))
            setBackgroundColor(Color.parseColor("#11000000"))

            // 状态文本
            statusText = TextView(context).apply {
                text = "空闲"
                setTextColor(Color.parseColor("#4CAF50"))
                textSize = baseStatusSize
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            addView(statusText)

            // 右下角缩放手柄
            addView(ResizeGripView(context).apply {
                val gripSize = dp(24)
                layoutParams = LinearLayout.LayoutParams(gripSize, gripSize).apply {
                    setMargins(dp(4), 0, 0, 0)
                    gravity = Gravity.BOTTOM or Gravity.END
                }
                setOnTouchListener { _, event ->
                    handleResizeTouch(event)
                    true
                }
            })
        }
        addView(statusRow)

        elevation = dp(16).toFloat()
    }

    // ─── 公开方法 ─────────────────────────────────

    fun appendStream(text: String) {
        post {
            if (text.length > terminalBuffer.length) {
                val newText = text.substring(terminalBuffer.length)
                terminalBuffer.append(newText)

                val styled = formatTerminalText(terminalBuffer.toString())
                terminalText.text = styled

                scrollView.post {
                    scrollView.fullScroll(View.FOCUS_DOWN)
                }
            }
        }
    }

    /** 清空终端缓冲区 — 新任务开始时调用 */
    fun clearStream() {
        post {
            terminalBuffer.clear()
            terminalText.text = "$ Pocket Agent 就绪\n"
        }
    }

    /** 设置会话消息列表 — 显示完整对话，支持滚动 */
    fun setMessages(messages: List<OverlayService.OverlayMessage>) {
        post {
            val sb = SpannableStringBuilder()
            for (msg in messages) {
                val start = sb.length
                if (msg.isUser) {
                    sb.append("你: ${msg.text}\n\n")
                    sb.setSpan(
                        ForegroundColorSpan(Color.parseColor("#90CAF9")),
                        start, sb.length,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                } else {
                    sb.append("AI: ${msg.text}\n\n")
                    sb.setSpan(
                        ForegroundColorSpan(Color.parseColor("#C8E6C9")),
                        start, sb.length,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
            if (sb.isEmpty()) {
                sb.append("$ Pocket Agent 就绪\n")
            }
            terminalText.text = sb
            scrollView.post {
                scrollView.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    fun updateStatus(status: String) {
        post {
            val color = when {
                status.contains("错误") || status.contains("失败") -> Color.parseColor("#F44336")
                status.contains("完成") -> Color.parseColor("#4CAF50")
                status.contains("执行") || status.contains("运行") -> Color.parseColor("#2196F3")
                status.contains("思考") -> Color.parseColor("#FFC107")
                else -> Color.parseColor("#4CAF50")
            }
            statusText.text = status
            statusText.setTextColor(color)
        }
    }

    // ─── 缩放触摸处理 ─────────────────────────────

    private fun handleResizeTouch(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                resizeInitialW = width
                resizeInitialH = height
                resizeInitialRawX = event.rawX
                resizeInitialRawY = event.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - resizeInitialRawX
                val deltaY = event.rawY - resizeInitialRawY
                val newW = (resizeInitialW + deltaX).toInt().coerceAtLeast(dp(200))
                val newH = (resizeInitialH + deltaY).toInt().coerceAtLeast(dp(150))
                onResize(newW, newH)
            }
        }
    }

    // ─── 缩放手柄自定义视图 ─────────────────────────

    private inner class ResizeGripView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#66FFFFFF")
            strokeWidth = dp(1.5f).toFloat()
            style = Paint.Style.STROKE
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val step = dp(4).toFloat()
            // 画三道斜线，模拟缩放手柄
            for (i in 0 until 3) {
                val startX = w - step * (3 - i)
                val startY = step * i
                canvas.drawLine(startX, startY, w, startY + (w - startX), paint)
            }
        }
    }

    // ─── 终端格式化 ───────────────────────────────

    private fun formatTerminalText(text: String): SpannableStringBuilder {
        val result = SpannableStringBuilder()
        val lines = text.split("\n")

        for (line in lines) {
            val start = result.length

            when {
                line.startsWith("[step") -> {
                    result.append("  → $line\n")
                    result.setSpan(
                        ForegroundColorSpan(Color.parseColor("#81C784")),
                        start, result.length,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                line.startsWith("[task]") -> {
                    result.append("  $line\n")
                    result.setSpan(
                        ForegroundColorSpan(Color.parseColor("#64B5F6")),
                        start, result.length,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                line.startsWith("[error]") || line.startsWith("❌") -> {
                    result.append("  $line\n")
                    result.setSpan(
                        ForegroundColorSpan(Color.parseColor("#EF5350")),
                        start, result.length,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                line.startsWith("[done]") -> {
                    result.append("  $line\n")
                    result.setSpan(
                        ForegroundColorSpan(Color.parseColor("#66BB6A")),
                        start, result.length,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                line.startsWith("[info]") -> {
                    result.append("  $line\n")
                    result.setSpan(
                        ForegroundColorSpan(Color.parseColor("#90A4AE")),
                        start, result.length,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                line.startsWith("$") -> {
                    result.append("$line\n")
                    result.setSpan(
                        ForegroundColorSpan(Color.parseColor("#00E5FF")),
                        start, result.length,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                else -> {
                    result.append("  $line\n")
                }
            }
        }
        return result
    }

    private fun dp(value: Float): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }

    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }
}
