package com.example.watchdog.data.model

/**
 * 按模型细分的用量数据
 */
data class ModelUsage(
    val modelName: String,
    val requestCount: Long = 0,
    val totalTokens: Long = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cost: String = "0.00"
)

/**
 * 某一天的按平台消耗数据（"每日消耗"趋势卡片的数据源）。
 * 来自 DeepSeek 控制台 usage/cost|amount 接口的 days[]（按天）。
 */
data class DailyUsage(
    val date: String,          // "2026-08-23"
    val platform: PlatformType,
    val totalTokens: Long = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val requests: Long = 0,
    val cost: Double = 0.0
)

/**
 * 某天某模型的按天消耗（"Token 用量统计"卡片按模型筛选/柱状图的数据源）。
 * 来自 DeepSeek 控制台 usage/cost|amount 接口的 days[].data[].model（按模型 × 按天）。
 * [inputTokens] 为真实输入量 = prompt + cacheHit + cacheMiss。
 */
data class DailyModelUsage(
    val date: String,          // "2026-08-23"
    val platform: PlatformType,
    val model: String,
    val totalTokens: Long = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val requests: Long = 0,
    val cost: Double = 0.0
)

/**
 * 订阅配额窗口（订阅制平台专用，如 Kimi Code 的 5小时/周/月 窗口）。
 * 所有字段可空：接口未实测，任何字段缺失都不影响其余字段展示。
 */
data class QuotaWindow(
    val name: String?,              // 窗口名称，如 "5小时" / "周" / "月"
    val used: Double? = null,       // 已用额度
    val remaining: Double? = null,  // 剩余额度
    val limit: Double? = null,      // 窗口总量
    val resetTime: Long? = null,    // 配额重置时间（epoch millis）
    val expiresAt: Long? = null     // 到期时间（epoch millis）
)

/**
 * 统一的平台额度数据模型。
 * 支持两种模式：
 * 1. 按量付费余额模式（现有 4 平台）：totalBalance / monthlyUsage / monthlyLimit；
 * 2. 订阅配额模式（如 Kimi Code）：planName / quotaWindows / boosterInfo，
 *    该模式下 totalBalance 无意义，保持默认值。
 */
data class QuotaInfo(
    val platform: PlatformType,
    val isAvailable: Boolean,
    val isConfigured: Boolean,
    val totalBalance: String = "0.00",
    val monthlyUsage: String = "0",
    val monthlyLimit: String = "0",
    val currency: String = "CNY",
    // 可用余额（区分可用/总余额的平台，如硅基流动）；其余平台保持 null
    val availableBalance: String? = null,
    val errorMessage: String? = null,
    val modelUsages: List<ModelUsage> = emptyList(),  // 按模型用量明细
    val dailyUsage: List<DailyUsage> = emptyList(),   // 按天消耗（趋势卡片数据源）
    val dailyModelUsage: List<DailyModelUsage> = emptyList(),  // 按模型 × 按天（Token 用量统计卡片数据源）
    val lastUpdated: Long = System.currentTimeMillis(),
    val isStale: Boolean = false,  // true 表示该数据来自本地缓存（离线回退）
    // ===== 以下为新增字段，均有默认值，不影响既有构造与缓存反序列化 =====
    val isEstimate: Boolean = false,        // true 表示余额为本地估算（如火山方舟），UI 应标注"估算"
    val planName: String? = null,           // 订阅套餐名（订阅配额模式）
    val quotaWindows: List<QuotaWindow> = emptyList(),  // 订阅配额窗口（订阅配额模式）
    val boosterInfo: String? = null,        // booster 描述（订阅配额模式）
    val dataSourceLabel: String? = null,    // 数据来源说明（如"网页控制台"），非官方接口数据时展示
    // 已配置网页会话但控制台抓取失败时的诊断信息（如"HTTP 429/429/200"），
    // 用于区分 WAF 拦截/接口变更/会话失效，UI 在卡片底部小字展示
    val consoleDiag: String? = null,
    // true 表示网页会话已失效且无法后台自动重登（如 MiMo 需人工过验证码），
    // UI 应显示"点击重新登录"入口拉起 WebView 登录页
    val needsRelogin: Boolean = false
) {
    val hasModelUsage: Boolean get() = modelUsages.isNotEmpty()
    val totalRequestCount: Long get() = modelUsages.sumOf { it.requestCount }
    val totalTokensUsed: Long get() = modelUsages.sumOf { it.totalTokens }

    /** 是否为订阅配额模式（存在套餐名或配额窗口） */
    val isSubscriptionMode: Boolean get() = planName != null || quotaWindows.isNotEmpty()

    /**
     * 订阅配额模式下所有窗口中最小的剩余占比（0.0~1.0），用于低余额/耗尽状态推导；
     * 按量付费模式或窗口缺少 remaining/limit 时为 null。
     */
    val lowestRemainingFraction: Double?
        get() = quotaWindows.mapNotNull { w ->
            val limit = w.limit
            val remaining = w.remaining
            if (limit != null && limit > 0 && remaining != null) {
                (remaining / limit).coerceIn(0.0, 1.0)
            } else null
        }.minOrNull()

    companion object {
        fun notConfigured(platform: PlatformType): QuotaInfo {
            return QuotaInfo(
                platform = platform,
                isAvailable = false,
                isConfigured = false
            )
        }

        fun error(platform: PlatformType, message: String): QuotaInfo {
            return QuotaInfo(
                platform = platform,
                isAvailable = false,
                isConfigured = true,
                errorMessage = message
            )
        }
    }
}

sealed class QuotaState {
    data object Loading : QuotaState()
    data class Success(val quotas: List<QuotaInfo>) : QuotaState()
    data class PartialSuccess(
        val quotas: List<QuotaInfo>,
        val failedPlatforms: List<PlatformType>
    ) : QuotaState()
    data class Error(val message: String) : QuotaState()
}

/**
 * 汇总已配置、无异常、非订阅且以 CNY 计价的平台总余额。
 * [freshOnly] 为 true 时仅统计本次成功获取（非离线缓存 isStale）的数据。
 * Token 计价平台（GLM）与订阅配额平台（Kimi Code）不参与金额汇总。
 */
fun List<QuotaInfo>.sumCnyBalance(freshOnly: Boolean = false): Double =
    filter {
        it.isConfigured && it.errorMessage == null && !it.isSubscriptionMode &&
            it.currency == "CNY" && (!freshOnly || !it.isStale)
    }.sumOf { it.totalBalance.toDoubleOrNull() ?: 0.0 }
