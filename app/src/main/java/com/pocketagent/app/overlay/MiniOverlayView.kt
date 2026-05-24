package com.pocketagent.app.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 迷你悬浮窗视图 — 药丸形，显示滚动文本
 *
 * 布局:
 * ┌──────────────────────────────────────┐
 * │ [icon]  [滚动输出...          ] [●]  │
 * └──────────────────────────────────────┘
 *
 * 特性:
 * - 半透明深色背景 + 圆角药丸形
 * - 左侧图标 (app icon)
 * - 中间最新流式输出行 (marquee 滚动)
 * - 右侧状态指示灯 (绿=运行, 黄=思考, 红=错误)
 * - 点击展开
 */
class MiniOverlayView(
    private val context: Context,
    private val onToggleExpand: () -> Unit,
    private val onMoveToCorner: () -> Unit
) : FrameLayout(context) {

    private val iconView: ImageView
    private val statusDot: View
    private val streamTextView: TextView
    private var pulseAnimator: ValueAnimator? = null

    init {
        val containerHeight = dp(40)

        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, containerHeight)

        // 背景 — 药丸形 (高圆角矩形)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(20).toFloat()
            setColor(Color.parseColor("#CC1A1A2E"))
            setStroke(dp(1.5f), Color.parseColor("#33FFFFFF"))
        }

        // 水平内容行
        val row = LinearLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, containerHeight)
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), 0)
        }
        addView(row)

        // 图标
        iconView = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                setMargins(0, 0, dp(6), 0)
            }
            setImageResource(android.R.drawable.ic_menu_manage) // 替换为真实 logo
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            alpha = 0.9f
        }
        row.addView(iconView)

        // 滚动输出文本
        streamTextView = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(100), LayoutParams.WRAP_CONTENT)
            setTextColor(Color.parseColor("#CCFFFFFF"))
            textSize = 10f
            maxLines = 1
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSelected = true // 强制 marquee 滚动
            text = ""
        }
        row.addView(streamTextView)

        // 状态指示灯
        statusDot = View(context).apply {
            val size = dp(8)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins(dp(6), 0, 0, 0)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#4CAF50"))
            }
        }
        row.addView(statusDot)

        setOnClickListener { onToggleExpand() }

        elevation = dp(8).toFloat()
    }

    /** 更新流式输出文本 — 显示最后一行，过长自动 marquee */
    fun setStreamText(text: String) {
        post {
            val lines = text.trimEnd().split("\n")
            val lastLine = lines.lastOrNull()?.trim() ?: ""
            streamTextView.text = lastLine
        }
    }

    fun updateStatus(status: String, isOperating: Boolean) {
        post {
            val color = when {
                isOperating -> Color.parseColor("#FFC107") // 操作中 - 黄色
                status.contains("错误") || status.contains("失败") -> Color.parseColor("#F44336") // 错误 - 红色
                status.contains("完成") -> Color.parseColor("#4CAF50") // 完成 - 绿色
                status.contains("执行") || status.contains("运行") -> Color.parseColor("#2196F3") // 运行 - 蓝色
                else -> Color.parseColor("#4CAF50") // 空闲 - 绿色
            }

            (statusDot.background as? GradientDrawable)?.setColor(color)

            // 运行时脉动动画
            if (isOperating || status.contains("运行")) {
                startPulse(color)
            } else {
                stopPulse()
            }
        }
    }

    private fun startPulse(color: Int) {
        if (pulseAnimator?.isRunning == true) return

        pulseAnimator = ValueAnimator.ofFloat(1f, 1.3f).apply {
            duration = 600
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animator ->
                val scale = animator.animatedValue as Float
                statusDot.scaleX = scale
                statusDot.scaleY = scale
                (statusDot.background as GradientDrawable).alpha = (255 * (1.5f - scale)).toInt()
            }
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        statusDot.scaleX = 1f
        statusDot.scaleY = 1f
        (statusDot.background as GradientDrawable).alpha = 255
    }

    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }

    private fun dp(value: Float): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }
}
