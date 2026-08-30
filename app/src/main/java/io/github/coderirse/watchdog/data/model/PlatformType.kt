package io.github.coderirse.watchdog.data.model

/**
 * 支持的AI平台枚举（纯数据层，不依赖任何 UI 类型）。
 *
 * 品牌色 / Logo / 缩写等视觉信息见 UI 层 PlatformVisual；
 * 网络端点定义在各 Retrofit Api 接口与 AppContainer 中，此处不再重复
 * （旧版 baseUrl/balanceEndpoint 字段与真实请求地址存在漂移风险，已移除）。
 */
enum class PlatformType(
    val displayName: String,
    /** SharedPreferences 键前缀（API Key / 启用开关 / 用量追踪等共用） */
    val keyPrefix: String,
    /**
     * 该平台是否支持"网页控制台会话"数据源：
     * 无官方余额/用量 API 时，可由用户登录网页控制台后自动抓取会话凭证
     * （Cookie / localStorage 值），转为调用网页控制台内部接口获取更完整的数据。
     */
    val supportsConsoleSession: Boolean = false
) {
    // DeepSeek：官方余额接口 + 控制台真实用量（会话为可选增强，
    // 未配置时回退官方 API + 本地月度估算）
    DEEPSEEK(
        displayName = "DeepSeek",
        keyPrefix = "deepseek",
        supportsConsoleSession = true
    ),
    // Kimi（月之暗面）：官方余额接口；可选增强：网页会话读取真实月度用量明细
    KIMI(
        displayName = "Kimi",
        keyPrefix = "kimi",
        supportsConsoleSession = true
    ),
    GLM(
        displayName = "智谱GLM",
        keyPrefix = "glm"
    ),
    SILICONFLOW(
        displayName = "硅基流动",
        keyPrefix = "siliconflow"
    ),
    // 方舟无"仅凭 API Key 查余额"的官方接口（费用中心需 AK/SK 签名），
    // 余额为用户手动填写的初始值 + 本地月度追踪的估算模式，不发起远程请求
    VOLCENGINE_ARK(
        displayName = "火山方舟",
        keyPrefix = "volcengine_ark"
    ),
    // Kimi Code 与 Kimi 开放平台为独立产品，API Key 独立存储，互不复用
    KIMI_CODE(
        displayName = "Kimi Code",
        keyPrefix = "kimi_code"
    ),
    // 小米 MiMo：官方无"仅凭 API Key"的余额/用量接口（2026-02 实测），
    // 数据源为网页控制台内部接口（需配置网页会话，会话必需）
    MIMO(
        displayName = "小米MiMo",
        keyPrefix = "mimo",
        supportsConsoleSession = true
    );
}
