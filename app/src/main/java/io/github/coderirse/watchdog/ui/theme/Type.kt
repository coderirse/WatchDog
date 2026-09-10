package io.github.coderirse.watchdog.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Set of Material typography styles to start with
val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    )
    /* Other default text styles to override
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
    */
)

/**
 * 余额大数字强调样式：加粗 + tabular 数字（等宽数字，避免金额刷新时跳动）。
 * 在 Typography 之外独立暴露，用于余额卡片等大数字场景。
 *
 * 字号从 32sp 提到 40sp：Hero 与品牌卡上的余额是整屏信息的视觉锚点，
 * 32sp 在 6.7" 屏幕上偏小、与 titleMedium 拉不开层级。
 */
val balanceNumeral = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Bold,
    fontSize = 40.sp,
    lineHeight = 48.sp,
    letterSpacing = (-0.5).sp,
    fontFeatureSettings = "tnum"
)

/**
 * 次级余额数字（订阅窗口、账户余额行等）：比 [balanceNumeral] 小一号，
 * 但仍保持 tabular 数字，避免与主数字争夺注意力。
 */
val secondaryNumeral = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.SemiBold,
    fontSize = 20.sp,
    lineHeight = 28.sp,
    letterSpacing = 0.sp,
    fontFeatureSettings = "tnum"
)
