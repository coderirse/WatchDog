package io.github.coderirse.watchdog.data.repository.providers

import io.github.coderirse.watchdog.data.api.GlmApi
import io.github.coderirse.watchdog.data.api.GlmCodingPlanApi
import io.github.coderirse.watchdog.data.api.GlmCodingPlanParser
import io.github.coderirse.watchdog.data.api.GlmTokenAccountsResponse
import io.github.coderirse.watchdog.data.local.SettingsStore
import io.github.coderirse.watchdog.data.model.ModelUsage
import io.github.coderirse.watchdog.data.model.PlatformType
import io.github.coderirse.watchdog.data.model.QuotaInfo
import io.github.coderirse.watchdog.data.model.QuotaWindow
import io.github.coderirse.watchdog.data.repository.PlatformQuotaProvider
import io.github.coderirse.watchdog.data.repository.ProviderSupport
import io.github.coderirse.watchdog.util.FormatUtils

/**
 * 智谱 GLM：官方资源包接口（tokenAccounts/list/my，Token 计价）
 * + 可选叠加 Coding Plan 订阅配额（配额接口鉴权特殊：Authorization 不带 Bearer 前缀）。
 */
class GlmProvider(
    private val glmApi: GlmApi,
    private val glmCodingPlanApi: GlmCodingPlanApi,
    private val settingsStore: SettingsStore
) : PlatformQuotaProvider {

    override val platform = PlatformType.GLM

    override suspend fun fetch(): QuotaInfo {
        val apiKey = settingsStore.getApiKey(platform) ?: return QuotaInfo.notConfigured(platform)
        val response = glmApi.getTokenAccounts("Bearer $apiKey")
        if (!response.isSuccessful) return ProviderSupport.httpError(platform, response.code())
        val body = response.body() ?: return QuotaInfo.error(platform, "响应为空")

        val base = buildResourceQuota(body)

        // 叠加 Coding Plan 订阅配额（可选增强）：GLM 用户的 API Key 可能同时有
        // 按量资源包与 Coding Plan 套餐，两者独立展示。
        if (apiKey.isBlank()) return base
        val plan = runCatching { glmCodingPlanApi.getQuotaLimit(apiKey) }.getOrNull()
        if (plan != null && plan.isSuccessful) {
            val planBody = plan.body()?.string()
            val result = planBody?.let(GlmCodingPlanParser::parse)
            if (result != null && result.success) {
                val quotaWindows = result.windows.mapNotNull { w ->
                    val usedPercent = w.usedPercent ?: return@mapNotNull null
                    // percentage 为已用百分比，换算 remaining/limit（limit 基准 100）
                    val remaining = (100.0 - usedPercent).coerceIn(0.0, 100.0)
                    QuotaWindow(
                        name = w.name,
                        used = usedPercent,
                        remaining = remaining,
                        limit = 100.0,
                        resetTime = w.nextResetTime,
                        expiresAt = null
                    )
                }
                if (quotaWindows.isNotEmpty() || result.planName != null) {
                    return base.copy(
                        planName = result.planName,
                        quotaWindows = quotaWindows,
                        boosterInfo = null,
                        // 有 Coding Plan 时该卡以订阅模式展示，monthlyUsage/limit 不作为余额语义
                        currency = if (base.currency == "Tokens") "额度" else base.currency
                    )
                }
            }
        }
        return base
    }

    /** 解析 GLM 官方资源包（按量余额）为 QuotaInfo。 */
    private fun buildResourceQuota(body: GlmTokenAccountsResponse): QuotaInfo {
        val allRows = body.rows ?: emptyList()
        // 优先统计有效期内资源包（status=EFFECTIVE）；接口不返回 status 时不过滤
        val effectiveRows = allRows.filter { it.status == null || it.status.equals("EFFECTIVE", true) }
        val rows = if (effectiveRows.isNotEmpty()) effectiveRows else allRows

        var totalRemaining = 0.0
        var totalAmount = 0.0
        val modelUsages = rows.mapNotNull { row ->
            val remain = row.tokenBalance ?: return@mapNotNull null
            totalRemaining += remain
            // 部分响应返回 totalAmount，部分返回 tokensMagnitude，两者兼容
            val amount = row.tokensMagnitude ?: row.totalAmount
            val used = if (amount != null) {
                totalAmount += amount
                (amount - remain).coerceAtLeast(0.0)
            } else {
                0.0
            }
            ModelUsage(
                modelName = row.resourcePackageName?.take(30)
                    ?: row.suitableModel
                    ?: row.tokenNo
                    ?: "未知",
                totalTokens = used.toLong()
            )
        }

        if (rows.isEmpty()) {
            return QuotaInfo(
                platform = platform,
                isAvailable = false,
                isConfigured = true,
                totalBalance = "0",
                monthlyUsage = "0",
                monthlyLimit = "0",
                currency = "Tokens",
                modelUsages = emptyList()
            )
        }

        val totalUsed = (totalAmount - totalRemaining).coerceAtLeast(0.0)
        return QuotaInfo(
            platform = platform,
            isAvailable = totalRemaining > 0 || totalAmount > 0,
            isConfigured = true,
            totalBalance = FormatUtils.formatNumber(totalRemaining.toLong()),
            monthlyUsage = FormatUtils.formatNumber(totalUsed.toLong()),
            monthlyLimit = FormatUtils.formatNumber(totalAmount.toLong()),
            currency = "Tokens",
            modelUsages = modelUsages
        )
    }
}
