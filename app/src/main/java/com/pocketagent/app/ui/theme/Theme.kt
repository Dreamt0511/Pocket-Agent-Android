package com.pocketagent.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ─── 淡蓝液态玻璃色板 ──────────────────────────

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF2C5F8A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E8F9),
    onPrimaryContainer = Color(0xFF1A3A5C),
    secondary = Color(0xFF5A9BD5),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8F2FC),
    onSecondaryContainer = Color(0xFF1A3A5C),
    tertiary = Color(0xFF7C8BA8),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF0F4FA),
    onTertiaryContainer = Color(0xFF1A3A5C),
    background = Color(0xFFF0F6FE),
    onBackground = Color(0xFF1A2A3C),
    surface = Color(0xFFF5FAFF),
    onSurface = Color(0xFF1A2A3C),
    surfaceVariant = Color(0xFFEBF2FA),
    onSurfaceVariant = Color(0xFF5A6B80),
    outline = Color(0xFFE0E8F2),
    outlineVariant = Color(0xFFEBF2FA),
    error = Color(0xFFDC3545),
    errorContainer = Color(0xFFFEF0F0),
    onError = Color(0xFFFFFFFF),
    onErrorContainer = Color(0xFF991B1B),
    inverseSurface = Color(0xFF1A2A3C),
    inverseOnSurface = Color(0xFFF0F6FE)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF8BC4F0),
    onPrimary = Color(0xFF0D1B30),
    primaryContainer = Color(0xFF1A3050),
    onPrimaryContainer = Color(0xFFD6E8F9),
    secondary = Color(0xFF6BAFE0),
    onSecondary = Color(0xFF0D1B30),
    secondaryContainer = Color(0xFF142840),
    onSecondaryContainer = Color(0xFFD6E8F9),
    tertiary = Color(0xFF8CA0B8),
    onTertiary = Color(0xFF0D1B30),
    tertiaryContainer = Color(0xFF182838),
    onTertiaryContainer = Color(0xFFE0E8F2),
    background = Color(0xFF0A1220),
    onBackground = Color(0xFFE0E8F2),
    surface = Color(0xFF0F1A2E),
    onSurface = Color(0xFFE0E8F2),
    surfaceVariant = Color(0xFF182838),
    onSurfaceVariant = Color(0xFF8CA0B8),
    outline = Color(0xFF203050),
    outlineVariant = Color(0xFF182838),
    error = Color(0xFFEF5A6A),
    errorContainer = Color(0xFF3A0A10),
    onError = Color(0xFFFFFFFF),
    onErrorContainer = Color(0xFFFECACA),
    inverseSurface = Color(0xFFF0F6FE),
    inverseOnSurface = Color(0xFF1A2A3C)
)

// ─── 液态玻璃自定义色 ─────────────────────────

object GlassColors {
    // 亮色 — 淡蓝水感
    val lightOrbPrimary = Color(0xFFB0CCE8)
    val lightOrbSecondary = Color(0xFFD4E0F0)
    val lightOrbAccent = Color(0xFFE8F0FA)
    val lightGlassTopHighlight = Color.White.copy(alpha = 0.92f)
    val lightGlassBottomGlow = Color(0xFFB0CCE8).copy(alpha = 0.15f)
    val lightGlassBorderStart = Color.White.copy(alpha = 0.95f)
    val lightGlassBorderEnd = Color(0xFFB0CCE8).copy(alpha = 0.35f)
    val lightGlassBgTop = Color.White.copy(alpha = 0.78f)
    val lightGlassBgBottom = Color(0xFFE8F2FC).copy(alpha = 0.55f)
    val lightNavIconBg = Color(0xFF2C5F8A).copy(alpha = 0.08f)

    // 暗色 — 深海蓝玻璃
    val darkOrbPrimary = Color(0xFF142840)
    val darkOrbSecondary = Color(0xFF1A2840)
    val darkOrbAccent = Color(0xFF203050)
    val darkGlassTopHighlight = Color.White.copy(alpha = 0.15f)
    val darkGlassBottomGlow = Color(0xFF2A5080).copy(alpha = 0.10f)
    val darkGlassBorderStart = Color.White.copy(alpha = 0.20f)
    val darkGlassBorderEnd = Color.White.copy(alpha = 0.04f)
    val darkGlassBgTop = Color.White.copy(alpha = 0.12f)
    val darkGlassBgBottom = Color.White.copy(alpha = 0.06f)
    val darkNavIconBg = Color.White.copy(alpha = 0.08f)
}

@Composable
fun isLightTheme(): Boolean = !isSystemInDarkTheme()

@Composable
fun orbColors(): List<Color> {
    val light = isLightTheme()
    return if (light) listOf(
        GlassColors.lightOrbPrimary,
        GlassColors.lightOrbSecondary,
        GlassColors.lightOrbAccent
    ) else listOf(
        GlassColors.darkOrbPrimary,
        GlassColors.darkOrbSecondary,
        GlassColors.darkOrbAccent
    )
}

@Composable
fun glassTopHighlight(): Color {
    val light = isLightTheme()
    return if (light) GlassColors.lightGlassTopHighlight else GlassColors.darkGlassTopHighlight
}

@Composable
fun glassBottomGlow(): Color {
    val light = isLightTheme()
    return if (light) GlassColors.lightGlassBottomGlow else GlassColors.darkGlassBottomGlow
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
