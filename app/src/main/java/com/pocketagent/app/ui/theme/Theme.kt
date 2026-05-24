package com.pocketagent.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Ollama 风格 — 极简黑白
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF000000),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF5F5F5),
    onPrimaryContainer = Color(0xFF000000),
    secondary = Color(0xFF6B6B6B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF0F0F0),
    onSecondaryContainer = Color(0xFF1A1A1A),
    tertiary = Color(0xFF6B6B6B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF0F0F0),
    onTertiaryContainer = Color(0xFF1A1A1A),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFF8F8F8),
    onSurfaceVariant = Color(0xFF6B6B6B),
    outline = Color(0xFFE5E5E5),
    outlineVariant = Color(0xFFF0F0F0),
    error = Color(0xFFDC2626),
    errorContainer = Color(0xFFFEF2F2),
    onError = Color(0xFFFFFFFF),
    onErrorContainer = Color(0xFF991B1B),
    inverseSurface = Color(0xFF1A1A1A),
    inverseOnSurface = Color(0xFFF5F5F5)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF2A2A2A),
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = Color(0xFFA0A0A0),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF1A1A1A),
    onSecondaryContainer = Color(0xFFE5E5E5),
    tertiary = Color(0xFFA0A0A0),
    onTertiary = Color(0xFF000000),
    tertiaryContainer = Color(0xFF1A1A1A),
    onTertiaryContainer = Color(0xFFE5E5E5),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF0A0A0A),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF141414),
    onSurfaceVariant = Color(0xFFA0A0A0),
    outline = Color(0xFF2A2A2A),
    outlineVariant = Color(0xFF1A1A1A),
    error = Color(0xFFEF4444),
    errorContainer = Color(0xFF450A0A),
    onError = Color(0xFFFFFFFF),
    onErrorContainer = Color(0xFFFECACA),
    inverseSurface = Color(0xFFF5F5F5),
    inverseOnSurface = Color(0xFF1A1A1A)
)

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
