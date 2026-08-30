package io.github.coderirse.watchdog.ui.components

import androidx.compose.ui.graphics.Color
import io.github.coderirse.watchdog.data.model.PlatformType

/**
 * 平台视觉信息（品牌色 / Logo 缩写 / Logo 地址）。
 *
 * 从数据层 PlatformType 中剥离：数据模型不应依赖 Compose 类型，
 * 视觉资源统一收敛在 UI 层，按平台枚举键控。
 */
data class PlatformVisual(
    val brandColor: Color,
    val initials: String,
    val logoUrl: String
)

private val PlatformVisuals: Map<PlatformType, PlatformVisual> = mapOf(
    PlatformType.DEEPSEEK to PlatformVisual(
        brandColor = Color(0xFF4D6BFE),
        initials = "DS",
        logoUrl = "https://registry.npmmirror.com/@lobehub/icons-static-png/latest/files/dark/deepseek.png"
    ),
    PlatformType.KIMI to PlatformVisual(
        brandColor = Color(0xFF6C4DFF),
        initials = "Ki",
        // 与 Kimi Code 同源图标（Moonshot/LobeHub）
        logoUrl = "https://registry.npmmirror.com/@lobehub/icons-static-png/latest/files/dark/moonshot.png"
    ),
    PlatformType.GLM to PlatformVisual(
        brandColor = Color(0xFF1976D2),
        initials = "GL",
        logoUrl = "https://registry.npmmirror.com/@lobehub/icons-static-png/latest/files/dark/zhipu.png"
    ),
    PlatformType.SILICONFLOW to PlatformVisual(
        brandColor = Color(0xFF00B96B),
        initials = "SF",
        logoUrl = "https://registry.npmmirror.com/@lobehub/icons-static-png/latest/files/dark/siliconcloud.png"
    ),
    PlatformType.VOLCENGINE_ARK to PlatformVisual(
        brandColor = Color(0xFFFA541C),
        initials = "AR",
        logoUrl = "https://registry.npmmirror.com/@lobehub/icons-static-png/latest/files/dark/volcengine.png"
    ),
    PlatformType.KIMI_CODE to PlatformVisual(
        brandColor = Color(0xFF111827),
        initials = "KC",
        logoUrl = "https://registry.npmmirror.com/@lobehub/icons-static-png/latest/files/dark/moonshot.png"
    ),
    // LobeHub 暂未收录 MiMo 图标，使用平台官网 favicon，加载失败回退缩写"Mi"
    PlatformType.MIMO to PlatformVisual(
        brandColor = Color(0xFFFF6900),
        initials = "Mi",
        logoUrl = "https://platform.xiaomimimo.com/favicon.png"
    )
)

/** 取平台视觉信息（所有枚举值均有定义）。 */
val PlatformType.visual: PlatformVisual
    get() = PlatformVisuals.getValue(this)
