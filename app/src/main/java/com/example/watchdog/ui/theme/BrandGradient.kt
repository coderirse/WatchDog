package com.example.watchdog.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 品牌渐变工具：品牌渐变卡片专用。
 * 渐变角度 135°（左上 → 右下）。
 */

/** 将颜色按 [factor]（0..1）压暗亮度，保持色相。 */
fun Color.darkened(factor: Float): Color {
    val f = factor.coerceIn(0f, 1f)
    return Color(
        red = red * f,
        green = green * f,
        blue = blue * f,
        alpha = alpha
    )
}

/**
 * 品牌渐变刷：135° 线性渐变，从 [brandColor] 过渡到其压暗 70% 亮度的变体。
 */
fun brandBrush(brandColor: Color): Brush = Brush.linearGradient(
    colors = listOf(brandColor, brandColor.darkened(0.7f)),
    start = Offset.Zero,
    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
)

/**
 * 低余额预警渐变：品牌色向预警橙过渡。
 */
fun warningBlendBrush(brandColor: Color): Brush = Brush.linearGradient(
    colors = listOf(brandColor, WarningAmber),
    start = Offset.Zero,
    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
)

/** 耗尽态渐变：红 → 橙。 */
val depletedBrush: Brush = Brush.linearGradient(
    colors = listOf(Color(0xFFDC2626), Color(0xFFF59E0B)),
    start = Offset.Zero,
    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
)

/** 品牌渐变卡片上的半透白常量：白字 / 次要白字 / 水印与进度条轨道遮罩。 */
val onBrand: Color = Color.White
val onBrandSecondary: Color = Color.White.copy(alpha = 0.78f)
val brandOverlay: Color = Color.White.copy(alpha = 0.12f)
