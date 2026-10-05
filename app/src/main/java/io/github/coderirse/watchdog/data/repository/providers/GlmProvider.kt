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
import kotlinx.coroutines.CancellationException

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
        val body = response.body() ?: return ProviderSupport.emptyResponse(platform)

        val base = buildResourceQuota(body)

        // 叠加 Coding Plan 订阅配额（可选增强）：GLM 用户的 API Key 可能同时有
        // 按量资源包与 Coding Plan 套餐，两者独立展示。
        if (apiKey.isBlank()) return base
        // 请求与读体一并纳入异常边界：可选增强失败（含 body 读取中断连）不得拖垮
        // 已成功获取的主数据；协程取消必须向上传播，不能当作错误吞掉
        val planBody = try {
            val resp = glmCodingPlanApi.getQuotaLimit(apiKey)
            if (resp.isSuccessful) resp.body()?.string() else {
                runCatching { resp.errorBody()?.close() }
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (planBody != null) {
            val result = GlmCodingPlanParser.parse(planBody)
            if (result.success) {
                val quotaWindows = result.windows.mapNotNull { w ->
                    // percentage 缺失不再丢弃窗口：保留名称与重置时间，
                    // QuotaWindow 字段全可空，UI 对缺失字段已有占位展示
                    val usedPercent = w.usedPercent
                    val remaining = usedPercent?.let { (100.0 - it).coerceIn(0.0, 100.0) }
                    QuotaWindow(
                        name = w.name,
                        used = usedPercent,
                        remaining = remaining,
                        limit = if (usedPercent != null) 100.0 else null,
                        resetTime = w.nextResetTime,
                        expiresAt = null
                    )
                }
                if (quotaWindows.isNotEmpty() || result.planName != null) {
                    return base.copy(
                        planName = result.planName,
                        quotaWindows = quotaWindows,
                        boosterInfo = null,
                        // 订阅解析成功即"拿到了可信的额度数值"（与 KimiCodeProvider 一致）：
                        // 纯 Coding Plan 用户的资源包行数为空、base.isAvailable=false，
                        // 原样继承会让 UI 误判为"耗尽"，与仍显示剩余的配额窗口自相矛盾
                        isAvailable = true,
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
        // 优先统计有效期内资源包（status=EFFECTIVE）。回退使用全部行仅限"接口完全不返回
        // status"的情形；返回了 status 且全部无效（全部过期）时如实按 0 处理——
        // 旧实现对该场景也回退，过期资源包的余额被当作可用余额展示
        val hasStatus = allRows.any { it.status != null }
        val effectiveRows = allRows.filter { it.status == null || it.status.equals("EFFECTIVE", true) }
        val rows = when {
            effectiveRows.isNotEmpty() -> effectiveRows
            hasStatus -> emptyList()
            else -> allRows
        }

        // 行存在但所有数值字段都解析失败：Gson 反射对改名字段会静默注入 null，
        // 这是"接口结构已变更"的特征，如实报错而非渲染成"耗尽"
        // （对照 KimiCodeProvider 对同场景的 unrecognizedResponse 处理）
        if (allRows.isNotEmpty() && allRows.all { r ->
                r.tokenBalance == null && r.tokensMagnitude == null && r.totalAmount == null
            }
        ) {
            return ProviderSupport.unrecognizedResponse(platform)
        }

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
