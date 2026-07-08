package com.example.watchdog.data.model

/**
 * 统一的平台额度数据模型
 */
data class QuotaInfo(
    val platform: PlatformType,
    val isAvailable: Boolean,
    val isConfigured: Boolean,           // 是否已配置API Key
    val totalBalance: String = "0.00",   // 总余额（金额类平台：CNY；GLM：Token限额）
    val monthlyUsage: String = "0",      // 本月已使用量
    val monthlyLimit: String = "0",      // 本月总量限制
    val currency: String = "CNY",
    val errorMessage: String? = null,
    val lastUpdated: Long = System.currentTimeMillis()
) {
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

/**
 * 平台查询状态
 */
sealed class QuotaState {
    data object Loading : QuotaState()
    data class Success(val quotas: List<QuotaInfo>) : QuotaState()
    data class PartialSuccess(
        val quotas: List<QuotaInfo>,
        val failedPlatforms: List<PlatformType>
    ) : QuotaState()
    data class Error(val message: String) : QuotaState()
}
