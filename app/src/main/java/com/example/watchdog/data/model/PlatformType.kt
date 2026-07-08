package com.example.watchdog.data.model

import androidx.compose.ui.graphics.Color

/**
 * 支持的AI平台枚举
 */
enum class PlatformType(
    val displayName: String,
    val baseUrl: String,
    val balanceEndpoint: String,
    val initials: String,
    val brandColor: Color,
    val logoUrl: String,          // LobeHub CDN Logo
    val description: String
) {
    DEEPSEEK(
        displayName = "DeepSeek",
        baseUrl = "https://api.deepseek.com",
        balanceEndpoint = "/user/balance",
        initials = "DS",
        brandColor = Color(0xFF4D6BFE),
        logoUrl = "https://registry.npmmirror.com/@lobehub/icons-static-png/latest/files/dark/deepseek.png",
        description = "DeepSeek AI平台"
    ),
    KIMI(
        displayName = "Kimi",
        baseUrl = "https://api.moonshot.cn",
        balanceEndpoint = "/v1/users/me/balance",
        initials = "Ki",
        brandColor = Color(0xFF6C4DFF),
        logoUrl = "https://registry.npmmirror.com/@lobehub/icons-static-png/latest/files/dark/moonshot.png",
        description = "Moonshot/Kimi AI平台"
    ),
    GLM(
        displayName = "智谱GLM",
        baseUrl = "https://open.bigmodel.cn",
        balanceEndpoint = "/api/biz/tokenAccounts/list/my",
        initials = "GL",
        brandColor = Color(0xFF1976D2),
        logoUrl = "https://registry.npmmirror.com/@lobehub/icons-static-png/latest/files/dark/zhipu.png",
        description = "智谱AI开放平台"
    ),
    SILICONFLOW(
        displayName = "硅基流动",
        baseUrl = "https://api.siliconflow.cn",
        balanceEndpoint = "/v1/user/info",
        initials = "SF",
        brandColor = Color(0xFF00B96B),
        logoUrl = "https://registry.npmmirror.com/@lobehub/icons-static-png/latest/files/dark/siliconcloud.png",
        description = "SiliconFlow AI推理平台"
    );
}
