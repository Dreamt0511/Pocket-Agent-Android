package com.pocketagent.app.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// ─── 液态玻璃卡片 ──────────────────────────────

/**
 * 液态玻璃卡片 — 三层深度结构
 *
 * 1. 外层阴影（宽泛柔和）
 * 2. 半透明渐变玻璃背景
 * 3. 顶部边缘高光（模拟光线折射）
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(14.dp),
    content: @Composable BoxScope.() -> Unit
) {
    val light = isLightTheme()

    Box(
        modifier = modifier
            .shadow(
                elevation = if (light) 3.dp else 2.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = if (light) 0.05f else 0.4f),
                spotColor = Color.Black.copy(alpha = if (light) 0.08f else 0.25f)
            )
            .clip(shape)
            .background(
                brush = Brush.verticalGradient(colors = glassBackgroundGradient()),
                shape = shape
            )
            .border(
                width = (0.5f).dp,
                brush = Brush.verticalGradient(colors = glassBorderGradient()),
                shape = shape
            )
    ) {
        // 内容层
        content()
        // 顶部边缘高光 — 在内容之上叠加，模拟玻璃顶端的光线折射
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .clip(shape)
                .background(glassTopHighlight())
                .align(Alignment.TopCenter)
        )
    }
}

/**
 * 玻璃 Surface — 比 GlassCard 更薄更透
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(14.dp),
    content: @Composable BoxScope.() -> Unit
) {
    val light = isLightTheme()

    Box(
        modifier = modifier
            .shadow(
                elevation = if (light) 1.dp else 0.5.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = if (light) 0.03f else 0.2f),
                spotColor = Color.Black.copy(alpha = if (light) 0.04f else 0.1f)
            )
            .clip(shape)
            .background(
                brush = Brush.verticalGradient(
                    colors = if (light) {
                        listOf(Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0.2f))
                    } else {
                        listOf(Color.White.copy(alpha = 0.07f), Color.White.copy(alpha = 0.04f))
                    }
                ),
                shape = shape
            )
            .border(
                width = (0.5f).dp,
                brush = Brush.verticalGradient(
                    colors = if (light) {
                        listOf(Color.White.copy(alpha = 0.7f), Color.White.copy(alpha = 0.3f))
                    } else {
                        listOf(Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.03f))
                    }
                ),
                shape = shape
            )
    ) {
        content()
    }
}

/**
 * 玻璃分隔线
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

// ─── 入场动画容器 ──────────────────────────────

/**
 * 带动画的 Staggered（错峰）容器
 *
 * 包装任意内容，在进入屏幕时执行滑动 + 渐入动画。
 * 多个相邻组件传入递增的 [delayMs] 即形成瀑布效果。
 *
 * @param delayMs 延迟毫秒（相对父组件挂载时间）
 */
@Composable
fun AnimatedStaggeredItem(
    modifier: Modifier = Modifier,
    delayMs: Int = 0,
    content: @Composable () -> Unit
) {
    var started by remember { mutableStateOf(false) }
    val progress by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = spring(
            dampingRatio = 0.72f,
            stiffness = Spring.StiffnessLow
        )
    )

    LaunchedEffect(Unit) {
        delay(delayMs.toLong())
        started = true
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                alpha = progress
                translationY = (1f - progress) * 24f
            }
    ) {
        content()
    }
}

/**
 * 带按压回弹动画的交互容器
 *
 * 包裹在可点击组件外层，按压时产生微缩反馈。
 */
@Composable
fun PressBounceContainer(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)
    )

    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
            ),
        content = content
    )
}
