package com.pocketagent.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ─── 基础色板 ──────────────────────────────────

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1A1D23),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF0F2F5),
    onPrimaryContainer = Color(0xFF1A1D23),
    secondary = Color(0xFF6B7280),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF0F1F3),
    onSecondaryContainer = Color(0xFF1A1D23),
    tertiary = Color(0xFF6B7280),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF0F1F3),
    onTertiaryContainer = Color(0xFF1A1D23),
    background = Color(0xFFEDF2FB),
    onBackground = Color(0xFF1A1D23),
    surface = Color(0xFFF7FAFF),
    onSurface = Color(0xFF1A1D23),
    surfaceVariant = Color(0xFFF0F4FC),
    onSurfaceVariant = Color(0xFF6B7280),
    outline = Color(0xFFE5E7EB),
    outlineVariant = Color(0xFFF0F1F3),
    error = Color(0xFFDC2626),
    errorContainer = Color(0xFFFEF2F2),
    onError = Color(0xFFFFFFFF),
    onErrorContainer = Color(0xFF991B1B),
    inverseSurface = Color(0xFF1A1D23),
    inverseOnSurface = Color(0xFFF5F5F5)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFF0F2F5),
    onPrimary = Color(0xFF1A1D23),
    primaryContainer = Color(0xFF2A2D35),
    onPrimaryContainer = Color(0xFFF0F2F5),
    secondary = Color(0xFF9CA3B0),
    onSecondary = Color(0xFF1A1D23),
    secondaryContainer = Color(0xFF1E2028),
    onSecondaryContainer = Color(0xFFE5E7EB),
    tertiary = Color(0xFF9CA3B0),
    onTertiary = Color(0xFF1A1D23),
    tertiaryContainer = Color(0xFF1E2028),
    onTertiaryContainer = Color(0xFFE5E7EB),
    background = Color(0xFF0C0E14),
    onBackground = Color(0xFFF0F2F5),
    surface = Color(0xFF141820),
    onSurface = Color(0xFFF0F2F5),
    surfaceVariant = Color(0xFF1E2028),
    onSurfaceVariant = Color(0xFF9CA3B0),
    outline = Color(0xFF2A2D35),
    outlineVariant = Color(0xFF1E2028),
    error = Color(0xFFEF4444),
    errorContainer = Color(0xFF450A0A),
    onError = Color(0xFFFFFFFF),
    onErrorContainer = Color(0xFFFECACA),
    inverseSurface = Color(0xFFF5F5F5),
    inverseOnSurface = Color(0xFF1A1D23)
)

// ─── 液态玻璃自定义色 ─────────────────────────

object GlassColors {
    // 亮色
    val lightOrbPrimary = Color(0xFFB8C5D6)
    val lightOrbSecondary = Color(0xFFD4C9B8)
    val lightGlassTopHighlight = Color.White.copy(alpha = 0.92f)
    val lightGlassBorderStart = Color.White.copy(alpha = 0.95f)
    val lightGlassBorderEnd = Color.White.copy(alpha = 0.4f)
    val lightGlassBgTop = Color.White.copy(alpha = 0.7f)
    val lightGlassBgBottom = Color.White.copy(alpha = 0.5f)
    val lightNavIconBg = Color(0xFF1A1D23).copy(alpha = 0.06f)

    // 暗色
    val darkOrbPrimary = Color(0xFF1E2A45)
    val darkOrbSecondary = Color(0xFF2A1E22)
    val darkGlassTopHighlight = Color.White.copy(alpha = 0.12f)
    val darkGlassBorderStart = Color.White.copy(alpha = 0.18f)
    val darkGlassBorderEnd = Color.White.copy(alpha = 0.04f)
    val darkGlassBgTop = Color.White.copy(alpha = 0.1f)
    val darkGlassBgBottom = Color.White.copy(alpha = 0.06f)
    val darkNavIconBg = Color.White.copy(alpha = 0.08f)
}

@Composable
fun isLightTheme(): Boolean = !isSystemInDarkTheme()

@Composable
fun orbColors(): List<Color> {
    val light = isLightTheme()
    return if (light) listOf(GlassColors.lightOrbPrimary, GlassColors.lightOrbSecondary)
    else listOf(GlassColors.darkOrbPrimary, GlassColors.darkOrbSecondary)
}

@Composable
fun glassTopHighlight(): Color {
    val light = isLightTheme()
    return if (light) GlassColors.lightGlassTopHighlight else GlassColors.darkGlassTopHighlight
}

@Composable
fun glassBorderGradient(): List<Color> {
    val light = isLightTheme()
    return if (light) listOf(GlassColors.lightGlassBorderStart, GlassColors.lightGlassBorderEnd)
    else listOf(GlassColors.darkGlassBorderStart, GlassColors.darkGlassBorderEnd)
}

@Composable
fun glassBackgroundGradient(): List<Color> {
    val light = isLightTheme()
    return if (light) listOf(GlassColors.lightGlassBgTop, GlassColors.lightGlassBgBottom)
    else listOf(GlassColors.darkGlassBgTop, GlassColors.darkGlassBgBottom)
}

@Composable
fun navIconBackground(): Color {
    val light = isLightTheme()
    return if (light) GlassColors.lightNavIconBg else GlassColors.darkNavIconBg
}

@Composable
fun PocketAgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
