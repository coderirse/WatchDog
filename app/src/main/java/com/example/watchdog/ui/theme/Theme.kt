package com.example.watchdog.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val LightColorScheme = lightColorScheme(
    primary = BrandIndigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBE4FF),
    onPrimaryContainer = BrandIndigo,
    secondary = SlateGrey,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2E8F0),
    onSecondaryContainer = LightTextPrimary,
    tertiary = SuccessGreen,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD1FAE5),
    onTertiaryContainer = Color(0xFF065F46),
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0),
    error = ErrorRed,
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF991B1B)
)

private val DarkColorScheme = darkColorScheme(
    primary = BrandIndigoLight,
    onPrimary = DarkBackground,
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color(0xFFDBE4FF),
    secondary = DarkTextSecondary,
    onSecondary = DarkBackground,
    secondaryContainer = Color(0xFF334155),
    onSecondaryContainer = DarkTextPrimary,
    tertiary = SuccessGreenDark,
    onTertiary = DarkBackground,
    tertiaryContainer = Color(0xFF065F46),
    onTertiaryContainer = Color(0xFFD1FAE5),
    background = DarkBackground,
    onBackground = DarkTextPrimary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkTextSecondary,
    outline = Color(0xFF475569),
    outlineVariant = Color(0xFF334155),
    error = ErrorRedDark,
    onError = DarkBackground,
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFEE2E2)
)

// 圆角规范：medium=16dp、large=20dp、small=8dp
private val WatchDogShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp)
)

/**
 * 状态语义色主题扩展，供卡片徽章等场景使用。
 * 不随 MaterialTheme.colorScheme 变化，需要明暗感知时直接按主题选择
 * 对应常量（如 success / successDark）。
 */
object WatchDogColors {
    val success = SuccessGreen
    val successDark = SuccessGreenDark
    val warning = WarningAmber
    val warningDark = WarningAmberDark
    val brandIndigo = BrandIndigo

    fun successFor(darkTheme: Boolean) = if (darkTheme) successDark else success
    fun warningFor(darkTheme: Boolean) = if (darkTheme) warningDark else warning
}

@Composable
fun WatchDogTheme(
    themeMode: ThemeMode = ThemeMode.FOLLOW_SYSTEM,
    // Dynamic color is available on Android 12+，默认关闭
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = WatchDogShapes,
        typography = Typography,
        content = content
    )
}
