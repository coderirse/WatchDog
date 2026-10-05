package io.github.coderirse.watchdog.data.repository.providers

import io.github.coderirse.watchdog.data.api.KimiCodeApi
import io.github.coderirse.watchdog.data.api.KimiCodeParser
import io.github.coderirse.watchdog.data.local.SettingsStore
import io.github.coderirse.watchdog.data.model.PlatformType
import io.github.coderirse.watchdog.data.model.QuotaInfo
import io.github.coderirse.watchdog.data.repository.PlatformQuotaProvider
import io.github.coderirse.watchdog.data.repository.ProviderSupport

/**
 * Kimi Code：订阅配额（GET v1/usages）。
 * 成功响应 JSON 未完整实测（2026-08 验证仅得 401），接口可能变更；
 * 解析全部为防御性兜底，任何字段缺失/类型不符都不抛异常到 UI。
 */
class KimiCodeProvider(
    private val kimiCodeApi: KimiCodeApi,
    private val settingsStore: SettingsStore
) : PlatformQuotaProvider {

    override val platform = PlatformType.KIMI_CODE

    override suspend fun fetch(): QuotaInfo {
        val apiKey = settingsStore.getApiKey(platform) ?: return QuotaInfo.notConfigured(platform)
        val response = kimiCodeApi.getUsages("Bearer $apiKey")
        if (!response.isSuccessful) return ProviderSupport.httpError(platform, response.code())

        val body = response.body() ?: return ProviderSupport.emptyResponse(platform)
        val planName = KimiCodeParser.parsePlanName(body.plan)
        val windows = body.windows?.map { KimiCodeParser.parseWindow(it) } ?: emptyList()
        // 注：顶层 expiresAt/resetTime 无法归属到具体窗口，暂不使用

        if (planName == null && windows.isEmpty()) {
            // HTTP 200 但解析不到任何可识别字段：接口结构很可能已变更
            return ProviderSupport.unrecognizedResponse(platform)
        }

        return QuotaInfo(
            platform = platform,
            isAvailable = true,
            isConfigured = true,
            currency = "额度",
            planName = planName,
            quotaWindows = windows,
            boosterInfo = KimiCodeParser.parseBooster(body.booster)
        )
    }
}
