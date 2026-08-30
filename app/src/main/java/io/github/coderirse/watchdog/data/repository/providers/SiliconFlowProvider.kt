package io.github.coderirse.watchdog.data.repository.providers

import io.github.coderirse.watchdog.data.api.SiliconFlowApi
import io.github.coderirse.watchdog.data.local.SettingsStore
import io.github.coderirse.watchdog.data.model.PlatformType
import io.github.coderirse.watchdog.data.model.QuotaInfo
import io.github.coderirse.watchdog.data.repository.PlatformQuotaProvider
import io.github.coderirse.watchdog.data.repository.ProviderSupport
import java.util.Locale

/** 硅基流动：官方 /v1/user/info 返回可用余额(balance)、充值余额(chargeBalance)、总余额(totalBalance)。 */
class SiliconFlowProvider(
    private val siliconFlowApi: SiliconFlowApi,
    private val settingsStore: SettingsStore
) : PlatformQuotaProvider {

    override val platform = PlatformType.SILICONFLOW

    override suspend fun fetch(): QuotaInfo {
        val apiKey = settingsStore.getApiKey(platform) ?: return QuotaInfo.notConfigured(platform)
        val resp = siliconFlowApi.getUserInfo("Bearer $apiKey")
        if (!resp.isSuccessful) return ProviderSupport.httpError(platform, resp.code())
        val data = resp.body()?.data
        val totalBalance = data?.totalBalance?.toDoubleOrNull() ?: 0.0
        val availableBalance = data?.balance?.toDoubleOrNull() ?: totalBalance
        return QuotaInfo(
            platform = platform,
            isAvailable = resp.body()?.status == true,
            isConfigured = true,
            totalBalance = String.format(Locale.US, "%.2f", totalBalance),
            availableBalance = String.format(Locale.US, "%.2f", availableBalance),
            currency = "CNY"
        )
    }
}
