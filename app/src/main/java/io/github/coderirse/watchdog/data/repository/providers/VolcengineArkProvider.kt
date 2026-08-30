package io.github.coderirse.watchdog.data.repository.providers

import io.github.coderirse.watchdog.data.local.SettingsStore
import io.github.coderirse.watchdog.data.model.PlatformType
import io.github.coderirse.watchdog.data.model.QuotaInfo
import io.github.coderirse.watchdog.data.repository.PlatformQuotaProvider
import java.util.Locale

/**
 * 火山方舟：官方无"仅凭方舟 API Key 查余额"的接口（费用中心/管控面需 AK/SK 签名），
 * 不发起任何远程请求，余额完全来自用户手动填写的初始余额 + 本地月度追踪，标记 isEstimate。
 */
class VolcengineArkProvider(
    private val settingsStore: SettingsStore
) : PlatformQuotaProvider {

    override val platform = PlatformType.VOLCENGINE_ARK

    override suspend fun fetch(): QuotaInfo {
        val initialBalance = settingsStore.getInitialBalance(platform)
            ?: return QuotaInfo(
                // 已配置 API Key 但未填写初始余额：可正常展示卡片与本月用量（无余额来源时为 0），
                // 不视为错误状态；UI 可依据 isEstimate && !isAvailable 提示用户填写初始余额
                platform = platform,
                isAvailable = false,
                isConfigured = true,
                totalBalance = "0.00",
                monthlyUsage = "0.00",
                currency = "CNY",
                isEstimate = true
            )

        // 与 DeepSeek/Kimi 相同的本地月度追踪方案：把用户填写的初始余额当作"余额快照"，
        // 月初自动重置起始值；用户在控制台对账后更新初始余额，差值即计为本月用量
        val usage = settingsStore.recordBalanceAndGetMonthlyUsage(platform, initialBalance)
        return QuotaInfo(
            platform = platform,
            isAvailable = true,
            isConfigured = true,
            totalBalance = String.format(Locale.US, "%.2f", initialBalance),
            monthlyUsage = if (usage < 0.01) "0.00" else String.format(Locale.US, "%.2f", usage),
            currency = "CNY",
            isEstimate = true
        )
    }
}
