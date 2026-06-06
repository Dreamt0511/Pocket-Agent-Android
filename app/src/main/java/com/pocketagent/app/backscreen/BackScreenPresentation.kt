package com.pocketagent.app.backscreen

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.Display
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 背屏终端显示 — 在背屏上实时渲染 Agent 执行输出的 Presentation
 *
 * 用传统 View 而非 Compose，因为 Presentation 运行在独立 Display 上，
 * 避免跨 Display 的 Compose 兼容问题。
 */
class BackScreenPresentation(context: Context, display: Display) : Presentation(context, display) {

    private lateinit var terminalText: TextView
    private lateinit var scrollView: ScrollView
    private val textBuffer = StringBuilder()
    private var lineCount = 0
    private val maxLines = 500

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#0D1117"))
        }

        // ── 顶栏 ──
        root.addView(TextView(context).apply {
            text = "  Pocket-Agent ⚡ 实时终端"
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#8B949E"))
            setPadding(10, 6, 10, 6)
            setBackgroundColor(Color.parseColor("#161B22"))
        })

        // ── 终端输出区 ──
        scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setPadding(10, 6, 10, 6)
        }

        terminalText = TextView(context).apply {
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#C9D1D9"))
            setLineSpacing(2f, 1f)
            text = "$ 背屏已就绪\n"
        }
        scrollView.addView(terminalText)
        root.addView(scrollView)

        // ── 底栏 ──
        root.addView(TextView(context).apply {
            text = "  实时输出 | Agent 执行状态"
            textSize = 8f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#484F58"))
            setPadding(10, 3, 10, 3)
            setBackgroundColor(Color.parseColor("#161B22"))
        })

        setContentView(root)
    }

    fun appendText(text: String) {
        if (!::terminalText.isInitialized) return
        textBuffer.append(text)
        lineCount = textBuffer.count { it == '\n' }
        if (lineCount > maxLines) {
            // 截断前 1/3 的行，避免内存溢出
            val lines = textBuffer.split("\n")
            val keep = lines.takeLast(maxLines * 2 / 3)
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
