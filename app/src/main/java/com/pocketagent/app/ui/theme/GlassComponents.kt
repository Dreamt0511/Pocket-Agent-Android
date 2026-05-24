package com.pocketagent.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 液态玻璃组件 — 极简黑白中的通透质感
 *
 * 核心思路：半透明白/黑卡片 + 微光边框 + 阴影叠层
 * 不使用 blur（性能 & API 兼容），靠 alpha 叠加产生"玻璃"感。
 */

/** 亮色模式下玻璃卡片背景色（白色半透，越透越像玻璃） */
private val glassLightBackground: Color get() = Color.White.copy(alpha = 0.55f)

/** 亮色模式下玻璃卡片边框色 */
private val glassLightBorder: Color get() = Color.White.copy(alpha = 0.85f)

/** 暗色模式下玻璃卡片背景色（深灰半透） */
private val glassDarkBackground: Color get() = Color(0xFF1A1A1A).copy(alpha = 0.6f)

/** 暗色模式下玻璃卡片边框色 */
private val glassDarkBorder: Color get() = Color.White.copy(alpha = 0.12f)

/** 亮色模式下阴影色（极淡黑） */
private val glassLightShadow: Color get() = Color.Black.copy(alpha = 0.04f)

/** 暗色模式下阴影色（几乎不可见，暗色不靠阴影） */
private val glassDarkShadow: Color get() = Color.White.copy(alpha = 0.02f)

/**
 * 判断当前主题是否为亮色
 */
@Composable
private fun isLightTheme(): Boolean {
    return MaterialTheme.colorScheme.background == Color.White
}

/**
 * 玻璃卡片 — 带半透明背景、微光边框和阴影
 *
 * 用法：直接替换 Card / Surface：
 *   GlassCard(shape = RoundedCornerShape(12.dp)) { ... }
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(12.dp),
    content: @Composable BoxScope.() -> Unit
) {
    val light = isLightTheme()

    Box(
        modifier = modifier
            .clip(shape)
            .background(
                color = if (light) glassLightBackground else glassDarkBackground,
                shape = shape
            )
            .border(
                width = if (light) (0.5f).dp else (0.5f).dp,
                color = if (light) glassLightBorder else glassDarkBorder,
                shape = shape
            )
    ) {
        content()
    }
}

/**
 * 玻璃 Surface — 比 GlassCard 更薄、更透，适合作为行/项的背景
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(12.dp),
    content: @Composable BoxScope.() -> Unit
) {
    val light = isLightTheme()

    Box(
        modifier = modifier
            .clip(shape)
            .background(
                color = if (light) Color.White.copy(alpha = 0.3f) else Color(0xFF1A1A1A).copy(alpha = 0.35f),
                shape = shape
            )
            .border(
                width = (0.5f).dp,
                color = if (light) Color.White.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.08f),
                shape = shape
            )
    ) {
        content()
    }
}

/**
 * 玻璃分隔线 — 细到几乎看不见，比普通 Divider 更温和
 */
@Composable
fun GlassDivider(modifier: Modifier = Modifier) {
    val light = isLightTheme()
    Box(
        modifier = modifier
            .height(Dp.Hairline)
            .fillMaxWidth()
            .background(
                if (light) Color.Black.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.06f)
            )
    )
}
