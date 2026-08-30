package io.github.coderirse.watchdog.data.repository.providers

import io.github.coderirse.watchdog.BuildConfig
import io.github.coderirse.watchdog.data.api.KimiApi
import io.github.coderirse.watchdog.data.api.KimiConsoleApi
import io.github.coderirse.watchdog.data.api.KimiConsoleParser
import io.github.coderirse.watchdog.data.local.SettingsStore
import io.github.coderirse.watchdog.data.local.WebSessionStore
import io.github.coderirse.watchdog.data.model.PlatformType
import io.github.coderirse.watchdog.data.model.QuotaInfo
import io.github.coderirse.watchdog.data.repository.PlatformQuotaProvider
import io.github.coderirse.watchdog.data.repository.ProviderSupport
import java.util.Locale

/**
 * Kimi（月之暗面）：官方 /v1/users/me/balance 余额 + 本地月度估算；
 * 无 API Key 但有网页会话时读控制台快照（登录时 DOM 采集），SSR HTML 解析作备用。
 */
class KimiProvider(
    private val kimiApi: KimiApi,
    private val kimiConsoleApi: KimiConsoleApi,
    private val webSessionStore: WebSessionStore,
    private val settingsStore: SettingsStore
) : PlatformQuotaProvider {

    override val platform = PlatformType.KIMI

    override suspend fun fetch(): QuotaInfo {
        val apiKey = settingsStore.getApiKey(platform)
        val raw = if (apiKey != null) fetchBalance("Bearer $apiKey") else fetchConsoleSession()

        // 官方接口无用量 API：本地月初余额快照推算（增量累计）；
        // 网页会话模式（dataSourceLabel 非空）不走本地推算
        return if (raw.isAvailable && raw.errorMessage == null && raw.dataSourceLabel == null) {
            val balance = raw.totalBalance.toDoubleOrNull()
            if (balance != null) {
                val usage = settingsStore.recordBalanceAndGetMonthlyUsage(platform, balance)
                raw.copy(monthlyUsage = ProviderSupport.fmtUsage(usage))
            } else raw
        } else raw
    }

    private suspend fun fetchBalance(authHeader: String): QuotaInfo {
        val response = kimiApi.getBalance(authHeader)
        if (!response.isSuccessful) return ProviderSupport.httpError(platform, response.code())
        val data = response.body()?.data
        val total = data?.availableBalance ?: 0.0
        return QuotaInfo(
            platform = platform,
            isAvailable = data != null,
            isConfigured = true,
            totalBalance = String.format(Locale.US, "%.2f", total),
            currency = "CNY"
        )
    }

    /** 无 API Key 但有网页会话：优先登录时 DOM 采集的快照，SSR 解析作备用。 */
    private suspend fun fetchConsoleSession(): QuotaInfo {
        val hasSession = runCatching { webSessionStore.hasWebSession(platform) }.getOrDefault(false)
        if (!hasSession) return QuotaInfo.notConfigured(platform)
        // 1) 优先读登录时采集的控制台快照（DOM 采集的余额/消费，真实可靠，非实时）
        val snap = runCatching { webSessionStore.getKimiSnapshot() }.getOrNull()
        if (snap != null && (snap.balance != null || snap.month != null || snap.total != null)) {
            return QuotaInfo(
                platform = platform, isAvailable = true, isConfigured = true,
                totalBalance = ProviderSupport.fmtAmount(snap.balance),
                monthlyUsage = ProviderSupport.fmtAmount(snap.month),
                currency = "CNY",
                dataSourceLabel = "网页控制台",
                modelUsages = emptyList(),
                boosterInfo = snap.total?.let { "累计消费 ¥${ProviderSupport.fmtAmount(it)}" }
            )
        }
        // 2) 备用：Cookie 请求 SSR HTML 解析（实时但解析复杂，可能失败）
        val cookie = runCatching { webSessionStore.getWebSessionCookie(platform) }.getOrNull()
        if (cookie.isNullOrBlank()) {
            return QuotaInfo(
                platform = platform, isAvailable = true, isConfigured = true,
                totalBalance = "0.00", currency = "CNY", dataSourceLabel = "网页控制台",
                boosterInfo = "已连接控制台会话，用量明细待接入"
            )
        }
        val result = runCatching {
            val resp = kimiConsoleApi.getConsoleHome(cookie)
            if (!resp.isSuccessful) return@runCatching null
            val html = resp.body()?.string()
            if (BuildConfig.DEBUG) android.util.Log.i("WatchDogRepo", "kimi SSR htmlLen=${html?.length ?: -1}")
            KimiConsoleParser.parse(html)
        }.getOrNull()
            ?: return QuotaInfo(
                platform = platform, isAvailable = true, isConfigured = true,
                totalBalance = "0.00", currency = "CNY", dataSourceLabel = "网页控制台",
                boosterInfo = "控制台已连接，数据解析中"
            )
        return QuotaInfo(
            platform = platform,
            isAvailable = true,
            isConfigured = true,
            totalBalance = ProviderSupport.fmtAmount(result.balance),
            monthlyUsage = ProviderSupport.fmtAmount(result.monthCost ?: result.totalCost),
            currency = "CNY",
            dataSourceLabel = "网页控制台",
            modelUsages = emptyList(),
            boosterInfo = result.totalCost?.let { "累计消费 ¥$it" }
        )
    }
}
