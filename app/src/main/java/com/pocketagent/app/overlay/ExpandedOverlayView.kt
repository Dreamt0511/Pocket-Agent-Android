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
    private val terminalText: TextView
    private val scrollView: NestedScrollView
    private val statusText: TextView

    private val terminalBuffer = StringBuilder()

    private var dragStartRawX = 0f
    private var dragStartRawY = 0f

    // 双指缩放手势检测 — 缩小到临界值自动关闭
    private val closeThreshold: Int get() = dp(80)
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            val newW = (width * scaleFactor).toInt()
            val newH = (height * scaleFactor).toInt()

            if (newW < closeThreshold) {
                onClose()
                return true
            }

            onResize(
                newW.coerceIn(dp(200), screenWidth),
                newH.coerceIn(dp(150), screenHeight)
            )
            return true
        }
    })

    // 缩放状态（右下角手柄拖拽）
    private var resizeInitialW = 0
    private var resizeInitialH = 0
    private var resizeInitialRawX = 0f
    private var resizeInitialRawY = 0f

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
            setPadding(dp(12), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(
                    dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat(),
                    0f, 0f, 0f, 0f
                )
                setColor(Color.parseColor("#331A1A2E"))
            }

            val titleText = TextView(context).apply {
                text = "Pocket Agent"
                setTextColor(Color.parseColor("#CCFFFFFF"))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
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
            setPadding(dp(12), dp(8), dp(12), dp(8))
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
            setPadding(dp(12), dp(4), dp(2), dp(4))
            setBackgroundColor(Color.parseColor("#11000000"))

            // 状态文本
            statusText = TextView(context).apply {
                text = "空闲"
                setTextColor(Color.parseColor("#4CAF50"))
                textSize = 10f
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

        // 统一触控：单指拖拽标题栏 + 双指缩放 + 缩放手柄
        setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (event.pointerCount == 1) {
                        dragStartRawX = event.rawX
                        dragStartRawY = event.rawY
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1) {
                        val dx = (event.rawX - dragStartRawX).toInt()
                        val dy = (event.rawY - dragStartRawY).toInt()
                        if (dx != 0 || dy != 0) {
                            onDrag(dx, dy)
                            dragStartRawX = event.rawX
                            dragStartRawY = event.rawY
                        }
                    }
                    true
                }
                else -> true
            }
        }
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
