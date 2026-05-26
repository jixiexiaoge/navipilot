package com.example.navipilot.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 车载深色主题（固定深色，禁用 dynamicColor）
 * 驾驶场景强制深色：减少夜间眩光，OLED 节能，品牌色稳定。
 */
private val AppColorScheme = darkColorScheme(
    primary                = Primary,
    onPrimary              = Color.White,
    primaryContainer       = PrimaryDark,
    onPrimaryContainer     = PrimaryLight,

    secondary              = Secondary,
    onSecondary            = Color.White,
    secondaryContainer     = SecondaryDark,
    onSecondaryContainer   = SecondaryLight,

    tertiary               = AccentPurple,
    onTertiary             = Color.White,

    background             = Surface900,   // OLED 纯黑
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
