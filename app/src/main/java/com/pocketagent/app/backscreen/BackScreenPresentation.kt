package com.pocketagent.app.backscreen

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 背屏终端显示 — 在副屏上用 WindowManager 渲染 Agent 实时输出
 *
 * 不继承 android.app.Presentation（Android 16 限制 TYPE_APPLICATION_PRESENTATION），
 * 改用 WindowManager + TYPE_APPLICATION_OVERLAY 直接添加到目标 Display。
 */
class BackScreenPresentation(context: Context, display: Display) {

    private val displayContext = context.createDisplayContext(display)
    private val wm = displayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private lateinit var terminalText: TextView
    private lateinit var scrollView: ScrollView
    private var rootView: View? = null
    private val textBuffer = StringBuilder()
    private var lineCount = 0
    private var _visible = false

    val isVisible: Boolean get() = _visible

    init {
        buildView()
    }

    private fun buildView() {
        val root = LinearLayout(displayContext).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#0D1117"))
        }

        // ── 顶栏 ──
        root.addView(TextView(displayContext).apply {
            text = "  Pocket-Agent 实时终端"
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#8B949E"))
            setPadding(10, 6, 10, 6)
            setBackgroundColor(Color.parseColor("#161B22"))
        })

        // ── 终端输出区 ──
        scrollView = ScrollView(displayContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setPadding(10, 6, 10, 6)
        }

        terminalText = TextView(displayContext).apply {
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#C9D1D9"))
            setLineSpacing(2f, 1f)
            text = "$ 背屏已就绪\n"
        }
        scrollView.addView(terminalText)
        root.addView(scrollView)

        // ── 底栏 ──
        root.addView(TextView(displayContext).apply {
            text = "  实时输出 | Agent 执行状态"
            textSize = 8f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#484F58"))
            setPadding(10, 3, 10, 3)
            setBackgroundColor(Color.parseColor("#161B22"))
        })

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        wm.addView(root, params)
        rootView = root
        _visible = true
    }

    fun appendText(text: String) {
        if (!::terminalText.isInitialized || !_visible) return
        textBuffer.append(text)
        lineCount = textBuffer.count { it == '\n' }
        if (lineCount > 500) {
            val lines = textBuffer.split("\n")
            val keep = lines.takeLast(340)
            textBuffer.clear()
            textBuffer.append(keep.joinToString("\n"))
            textBuffer.append("\n")
        }
        terminalText.post {
            terminalText.text = formatOutput(textBuffer.toString())
            scrollView.post {
                scrollView.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    fun clearText() {
        textBuffer.clear()
        lineCount = 0
        terminalText.post {
            terminalText.text = "$ 背屏已就绪\n"
        }
    }

    fun dismiss() {
        if (_visible) {
            try {
                rootView?.let { wm.removeViewImmediate(it) }
            } catch (_: Exception) {
            }
            rootView = null
            _visible = false
        }
    }

    private fun formatOutput(text: String): SpannableStringBuilder {
        val result = SpannableStringBuilder()
        val lines = text.split("\n")

        for (line in lines) {
            val start = result.length
            when {
                line.startsWith("[step") || line.startsWith("[步骤") -> {
                    result.append("  $line\n")
                    result.setSpan(ForegroundColorSpan(Color.parseColor("#7EE787")), start, result.length, 0)
                }
                line.startsWith("[task]") -> {
                    result.append("  $line\n")
                    result.setSpan(ForegroundColorSpan(Color.parseColor("#58A6FF")), start, result.length, 0)
                }
                line.startsWith("[error]") || line.contains("错误") || line.contains("失败") -> {
                    result.append("  $line\n")
                    result.setSpan(ForegroundColorSpan(Color.parseColor("#F85149")), start, result.length, 0)
                }
                line.startsWith("[done]") || line.contains("完成") || line.contains("成功") -> {
                    result.append("  $line\n")
                    result.setSpan(ForegroundColorSpan(Color.parseColor("#3FB950")), start, result.length, 0)
                }
                line.startsWith("[info]") -> {
                    result.append("  $line\n")
                    result.setSpan(ForegroundColorSpan(Color.parseColor("#8B949E")), start, result.length, 0)
                }
                line.startsWith("$") || line.startsWith("agent@") -> {
                    result.append("$line\n")
                    result.setSpan(ForegroundColorSpan(Color.parseColor("#79C0FF")), start, result.length, 0)
                }
                line.startsWith("  →") -> {
                    result.append("$line\n")
                    result.setSpan(ForegroundColorSpan(Color.parseColor("#D2A8FF")), start, result.length, 0)
                }
                line.startsWith("╔") || line.startsWith("╚") || line.startsWith("║") -> {
                    result.append("$line\n")
                    result.setSpan(ForegroundColorSpan(Color.parseColor("#FFA657")), start, result.length, 0)
                }
                else -> {
                    result.append("  $line\n")
                }
            }
        }
        return result
    }
}
