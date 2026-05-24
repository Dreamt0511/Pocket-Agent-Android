package com.pocketagent.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.sin

/**
 * 液态玻璃动态背景
 *
 * 在 Canvas 上绘制多层半透明光晕，以极慢速度漂移，
 * 叠加形成类似液态玻璃表面的深度与流动感。
 */
@Composable
fun AnimatedBackground(
    orbColors: List<Color>,
    modifier: Modifier = Modifier
) {
    if (orbColors.isEmpty()) return

    val infiniteTransition = rememberInfiniteTransition(label = "bg_orbs")

    val orbStates = remember(orbColors.size) {
        orbColors.indices.map { i ->
            val speed = 1f + i * 0.15f
            OrbState(
                phaseX = i * 2.094f,
                phaseY = i * 1.571f,
                speed = speed,
                durX = 16000 + i * 4000,
                durY = 19000 + i * 3000
            )
        }
    }

    val xAnims = orbStates.mapIndexed { index, s ->
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(s.durX, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "orb_x_$index"
        )
    }

    val yAnims = orbStates.mapIndexed { index, s ->
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(s.durY, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "orb_y_$index"
        )
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val maxDim = maxOf(w, h)

        orbStates.forEachIndexed { i, state ->
            val xOff = xAnims[i].value
            val yOff = yAnims[i].value

            val cx = w * (0.2f + 0.6f * (0.5f + 0.5f * sin(xOff * 6.283f * state.speed + state.phaseX)))
            val cy = h * (0.15f + 0.7f * (0.5f + 0.5f * cos(yOff * 6.283f * state.speed + state.phaseY)))
            val baseRadius = maxDim * 0.28f

            // 多层同心圆叠加模拟柔和发光，无 Blur 依赖
            for (layer in 0..8) {
                val r = baseRadius + layer * maxDim * 0.025f
                val alpha = 0.045f / (layer * 0.4f + 1f)
                drawCircle(
                    color = orbColors[i],
                    radius = r,
                    center = Offset(cx, cy),
                    alpha = alpha
                )
            }
        }
    }
}

private data class OrbState(
    val phaseX: Float,
    val phaseY: Float,
    val speed: Float,
    val durX: Int,
    val durY: Int
)
