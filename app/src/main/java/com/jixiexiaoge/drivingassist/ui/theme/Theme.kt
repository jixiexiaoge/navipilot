package com.jixiexiaoge.drivingassist.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 车载深色主题（固定深色，禁用 dynamicColor）
 */
private val AppColorScheme = darkColorScheme(
    primary                = AccentCyan,
    onPrimary              = Color.White,
    primaryContainer       = AccentCyan.copy(alpha = 0.15f),
    onPrimaryContainer     = AccentCyan,

    secondary              = Secondary,
    onSecondary            = Color.White,
    secondaryContainer     = Secondary.copy(alpha = 0.15f),
    onSecondaryContainer   = Secondary,

    tertiary               = AccentPurple,
    onTertiary             = Color.White,

    background             = Surface900,
    onBackground           = TextPrimary,

    surface                = Surface800,
    onSurface              = TextPrimary,
    surfaceVariant         = Surface700,
    onSurfaceVariant       = TextSecondary,

    outline                = Surface600,
    outlineVariant         = Surface500,

    error                  = Error,
    onError                = Color.White,
    errorContainer         = Color(0xFF7F1D1D),
    onErrorContainer       = Color(0xFFFECACA),
)

// ============================================
// Design Tokens — Spacing & Radius
// ============================================
data class AppSpacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
)

data class AppRadius(
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
)

val LocalAppSpacing = staticCompositionLocalOf { AppSpacing() }
val LocalAppRadius = staticCompositionLocalOf { AppRadius() }

@Composable
fun NavipilotTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = AppColorScheme,
        typography  = Typography,
        content     = content
    )
}
