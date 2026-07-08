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
 * 统一的平台额度数据模型
 */
data class QuotaInfo(
    val platform: PlatformType,
    val isAvailable: Boolean,
    val isConfigured: Boolean,
    val totalBalance: String = "0.00",
    val monthlyUsage: String = "0",
    val monthlyLimit: String = "0",
    val currency: String = "CNY",
    val errorMessage: String? = null,
    val modelUsages: List<ModelUsage> = emptyList(),  // 按模型用量明细
    val lastUpdated: Long = System.currentTimeMillis()
) {
    val hasModelUsage: Boolean get() = modelUsages.isNotEmpty()
    val totalRequestCount: Long get() = modelUsages.sumOf { it.requestCount }
    val totalTokensUsed: Long get() = modelUsages.sumOf { it.totalTokens }

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
