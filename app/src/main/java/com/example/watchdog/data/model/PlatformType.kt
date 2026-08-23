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
    val description: String,
    /**
     * 该平台是否支持"网页控制台会话"数据源：
     * 无官方余额/用量 API 时，可让用户粘贴登录会话令牌（Cookie/localStorage 值），
     * 转为调用网页控制台内部接口获取更完整的数据。当前仅小米 MiMo 开启。
     */
    val supportsConsoleSession: Boolean = false
) {
    DEEPSEEK(
        displayName = "DeepSeek",
        baseUrl = "https://api.deepseek.com",
        balanceEndpoint = "/user/balance",
        initials = "DS",
        brandColor = Color(0xFF4D6BFE),
        logoUrl = "https://registry.npmmirror.com/@lobehub/icons-static-png/latest/files/dark/deepseek.png",
        description = "DeepSeek AI平台",
        // DeepSeek 控制台内部接口可提供真实用量明细（按模型/日期），会话为可选增强：
        // 未配置时回退官方余额接口 + 本地月度估算
        supportsConsoleSession = true
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
    ),
    VOLCENGINE_ARK(
        displayName = "火山方舟",
        baseUrl = "https://ark.cn-beijing.volces.com/api/v3/",
        // 方舟无"仅凭 API Key 查余额"的官方接口（费用中心需 AK/SK 签名），
        // 余额为用户手动填写的初始值 + 本地月度追踪的估算模式，不发起远程请求
        balanceEndpoint = "",
        initials = "AR",
        brandColor = Color(0xFFFA541C),
        logoUrl = "https://registry.npmmirror.com/@lobehub/icons-static-png/latest/files/dark/volcengine.png",
        description = "火山方舟大模型平台（余额为本地估算，需手动填写初始余额）"
    ),
    KIMI_CODE(
        displayName = "Kimi Code",
        baseUrl = "https://api.kimi.com/coding/",
        balanceEndpoint = "v1/usages",
        initials = "KC",
        brandColor = Color(0xFF111827),
        // 与 Kimi 开放平台同源图标；Kimi Code 为独立产品，API Key 与 Kimi 开放平台互不复用
        logoUrl = "https://registry.npmmirror.com/@lobehub/icons-static-png/latest/files/dark/moonshot.png",
        description = "Kimi Code 编程订阅（API Key 独立于 Kimi 开放平台）"
    ),
    // 小米 MiMo：官方无"仅凭 API Key"的余额/用量接口（2026-02 实测），
    // 数据源为网页控制台内部接口（需用户粘贴登录会话令牌 api-platform_ph），
    // 可返回真实余额与 Token Plan 订阅配额用量
    MIMO(
        displayName = "小米MiMo",
        baseUrl = "https://api.xiaomimimo.com/",
        // 无官方余额 API：数据来自网页控制台内部接口，见 supportsConsoleSession
        balanceEndpoint = "",
        initials = "Mi",
        brandColor = Color(0xFFFF6900),
        // LobeHub 暂未收录 MiMo 图标，使用平台官网 favicon 作为 Logo，加载失败回退缩写"Mi"
        logoUrl = "https://platform.xiaomimimo.com/favicon.png",
        description = "小米 MiMo API 开放平台（余额/用量来自网页控制台，需配置网页会话令牌）",
        supportsConsoleSession = true
    );
}
