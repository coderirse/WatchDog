package io.github.coderirse.watchdog.ui.theme

import androidx.compose.runtime.compositionLocalOf
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

/** sRGB 相对亮度（0..1），用于选择品牌渐变上的文字色。 */
fun Color.luminance(): Float {
    fun lin(c: Float) = if (c <= 0.03928f) c / 12.92f else Math.pow(((c - 0.055) / 1.055).toDouble(), 2.4).toFloat()
    return 0.2126f * lin(red) + 0.7152f * lin(green) + 0.0722f * lin(blue)
}

/**
 * 品牌渐变上的主文字色：按品牌色亮度自适应。
 * 亮色系（硅基流动绿 / 火山方舟橙 / MiMo 橙）上白字对比度不足 4.5:1，改用深色文字；
 * 深色系（DeepSeek 蓝 / GLM 蓝 / Kimi 紫等）保持白字。
 */
fun onBrandFor(brandColor: Color): Color =
    if (brandColor.luminance() > 0.22f) Color(0xFF0F172A) else Color.White

/**
 * 品牌渐变刷：135° 线性渐变，从 [brandColor] 过渡到其压暗 70% 亮度的变体。
 */
fun brandBrush(brandColor: Color): Brush = Brush.linearGradient(
    colors = listOf(brandColor, brandColor.darkened(0.7f)),
    start = Offset.Zero,
    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
)

/**
 * 低余额预警渐变：品牌色向深琥珀过渡（原亮橙上白字对比度不足，已压暗）。
 */
fun warningBlendBrush(brandColor: Color): Brush = Brush.linearGradient(
    colors = listOf(brandColor, Color(0xFFB45309)),
    start = Offset.Zero,
    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
)

/** 耗尽态渐变：深红 → 红（原 红→亮橙 尾端白字对比度不足，已压暗）。 */
val depletedBrush: Brush = Brush.linearGradient(
    colors = listOf(Color(0xFF991B1B), Color(0xFFDC2626)),
    start = Offset.Zero,
    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
)

/** 品牌渐变卡片上的半透白常量：白字 / 次要白字 / 水印与进度条轨道遮罩。
 *  深色固定渐变（Hero 卡）专用；品牌色卡片请用 LocalOnBrand 系列自适应值。 */
val onBrand: Color = Color.White
val onBrandSecondary: Color = Color.White.copy(alpha = 0.78f)
val brandOverlay: Color = Color.White.copy(alpha = 0.12f)

/**
 * 品牌渐变卡文字/遮罩色 CompositionLocal：
 * BrandQuotaCard 依据品牌色亮度 provide 一组（白或深），子组件统一消费，
 * 避免亮色系品牌卡（绿/橙）上白字对比度不达标。
 */
val LocalOnBrand = compositionLocalOf { Color.White }
val LocalOnBrandSecondary = compositionLocalOf { Color.White.copy(alpha = 0.85f) }
val LocalBrandOverlay = compositionLocalOf { Color.White.copy(alpha = 0.12f) }
