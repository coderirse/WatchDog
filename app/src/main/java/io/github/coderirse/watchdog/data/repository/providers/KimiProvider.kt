package io.github.coderirse.watchdog.data.repository.providers

import io.github.coderirse.watchdog.BuildConfig
import io.github.coderirse.watchdog.data.api.KimiApi
import io.github.coderirse.watchdog.data.api.KimiConsoleApi
import io.github.coderirse.watchdog.data.api.KimiConsoleParser
import io.github.coderirse.watchdog.data.local.SettingsStore
import io.github.coderirse.watchdog.data.local.WebSessionStore
import io.github.coderirse.watchdog.data.model.MonthlyUsageSource
import io.github.coderirse.watchdog.data.model.PlatformType
import io.github.coderirse.watchdog.data.model.QuotaInfo
import io.github.coderirse.watchdog.data.repository.PlatformQuotaProvider
import io.github.coderirse.watchdog.data.repository.ProviderSupport
import java.util.Locale

/**
 * Kimi（月之暗面）：官方 /v1/users/me/balance 余额 + 本地月度估算；
 * 无 API Key 但有网页会话时读控制台数据（登录时 DOM 采集的快照优先，SSR HTML 解析备用）。
 *
 * ⚠️ `isAvailable` 一律只在**确实取到可信数值**时为 true。
 * 历史缺陷（本次修复）：无凭据 cookie 或 HTML 解析失败时返回
 * `isAvailable = true` + `totalBalance = "0.00"`，该假 0 余额会
 * ① 通过 `sumCnyBalance()` 混入 Hero 总余额；
 * ② 通过 `DashboardViewModel` 的"无异常"判断写入余额趋势快照，造成折线跳水；
 * ③ 让卡片因 `isAvailable = true` 仍显示"正常"，且不提供重登入口。
 *
 * 另一处口径修正：快照里只有"累计消费"而没有"本月消费"时，不再把累计值填进
 * `monthlyUsage`（此前会把累计消费显示成"本月用量"，数字严重偏大）；
 * 改为 `monthlyUsage = ""`，由 UI 显示占位符。
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

        if (raw.isAvailable && raw.errorMessage == null && raw.dataSourceLabel == null) {
            val snapshot = loadSnapshot()
            if (snapshot?.monthUsage != null) {
                // 有控制台快照的真实本月消费：优先于本地推算
                return raw.copy(
                    monthlyUsage = snapshot.monthUsage,
                    monthlyUsageSource = MonthlyUsageSource.SERVER,
                    boosterInfo = snapshot.boosterLabel()
                )
            }
            // 官方接口不提供用量：用本地增量累计推算，并明确标注为估算口径
            val balance = raw.totalBalance.toDoubleOrNull()
            if (balance != null) {
                val usage = settingsStore.recordBalanceAndGetMonthlyUsage(platform, balance)
                return raw.copy(
                    monthlyUsage = ProviderSupport.fmtUsage(usage),
                    monthlyUsageSource = MonthlyUsageSource.LOCAL_ESTIMATE
                )
            }
        }
        return raw
    }

    private suspend fun fetchBalance(authHeader: String): QuotaInfo {
        val response = kimiApi.getBalance(authHeader)
        if (!response.isSuccessful) return ProviderSupport.httpError(platform, response.code())
        val data = response.body()?.data ?: return ProviderSupport.emptyResponse(platform)
        val total = data.availableBalance ?: 0.0
        return QuotaInfo(
            platform = platform,
            isAvailable = true,
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
        loadSnapshot()?.let { snapshot ->
            return QuotaInfo(
                platform = platform,
                isAvailable = true,
                isConfigured = true,
                totalBalance = snapshot.balance,
                monthlyUsage = snapshot.monthUsage.orEmpty(),
                monthlyUsageSource = MonthlyUsageSource.SERVER,
                currency = "CNY",
                dataSourceLabel = "网页控制台",
                modelUsages = emptyList(),
                boosterInfo = snapshot.boosterLabel()
            )
        }

        // 2) 备用：Cookie 请求 SSR HTML 解析（实时但解析复杂，可能失败）
        val cookie = runCatching { webSessionStore.getWebSessionCookie(platform) }.getOrNull()
        if (cookie.isNullOrBlank()) {
            // 会话存在但凭据不完整：如实报错并提供重登入口，
            // 不再返回 isAvailable=true + "0.00" 的假数据
            return QuotaInfo.error(platform, "网页会话凭据不完整，请重新登录获取")
                .copy(needsRelogin = true)
        }
        return when (val outcome = fetchConsoleHtml(cookie)) {
            is ConsoleOutcome.Failure -> QuotaInfo
                .error(platform, outcome.message)
                .copy(needsRelogin = true, consoleDiag = outcome.diag)
            is ConsoleOutcome.Success -> {
                val data = outcome.data
                QuotaInfo(
                    platform = platform,
                    isAvailable = true,
                    isConfigured = true,
                    totalBalance = ProviderSupport.fmtAmount(data.balance),
                    monthlyUsage = ProviderSupport.fmtAmount(data.monthCost ?: data.totalCost),
                    monthlyUsageSource = MonthlyUsageSource.SERVER,
                    currency = "CNY",
                    dataSourceLabel = "网页控制台",
                    modelUsages = emptyList(),
                    boosterInfo = data.totalCost?.let { "累计消费 ¥${ProviderSupport.fmtAmount(it)}" }
                )
            }
        }
    }

    /** 登录时采集的控制台快照；三项全空视为无快照。 */
    private suspend fun loadSnapshot(): Snapshot? {
        val snap = runCatching { webSessionStore.getKimiSnapshot() }.getOrNull() ?: return null
        if (snap.balance == null && snap.month == null && snap.total == null) return null
        return Snapshot(
            balance = ProviderSupport.fmtAmount(snap.balance),
            monthUsage = snap.month?.let { ProviderSupport.fmtAmount(it) },
            totalCost = snap.total
        )
    }

    private sealed interface ConsoleOutcome {
        data class Success(val data: KimiConsoleParser.Result) : ConsoleOutcome
        data class Failure(val message: String, val diag: String?) : ConsoleOutcome
    }

    /** SSR HTML 抓取与解析，把"网络/HTTP 失败"与"解析不出数据"区分为两种失败语义。 */
    private suspend fun fetchConsoleHtml(cookie: String): ConsoleOutcome {
        val response = runCatching { kimiConsoleApi.getConsoleHome(cookie) }.getOrNull()
            ?: return ConsoleOutcome.Failure("控制台请求失败，请检查网络后重试", "控制台网络异常")
        if (!response.isSuccessful) {
            return ConsoleOutcome.Failure(
                message = if (response.code() == 401 || response.code() == 403) {
                    "网页会话已过期，请重新登录"
                } else {
                    "控制台请求被拒绝（HTTP ${response.code()}），请稍后重试"
                },
                diag = "控制台 HTTP ${response.code()}"
            )
        }
        val html = runCatching { response.body()?.string() }.getOrNull()
        if (BuildConfig.DEBUG) {
            android.util.Log.i("WatchDogRepo", "kimi SSR htmlLen=${html?.length ?: -1}")
        }
        val parsed = html?.let { KimiConsoleParser.parse(it) }
            ?: return ConsoleOutcome.Failure(
                message = "控制台页面结构已变更，暂时无法读取用量",
                diag = "页面解析无结果"
            )
        return ConsoleOutcome.Success(parsed)
    }

    /** 快照的内部形态：余额与本月消费已格式化；累计消费单独保留，不再冒充本月用量。 */
    private data class Snapshot(
        val balance: String,
        val monthUsage: String?,
        val totalCost: String?
    ) {
        /** 累计消费展示文案（仅作附加信息，与"本月用量"分开呈现）。 */
        fun boosterLabel(): String? =
            totalCost?.let { "累计消费 ¥${ProviderSupport.fmtAmount(it)}" }
    }
}
