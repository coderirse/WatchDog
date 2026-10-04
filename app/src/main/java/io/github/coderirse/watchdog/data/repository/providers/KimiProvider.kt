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
 * 改为 `monthlyUsage = ""`，由 UI 显示占位符。SSR 路径同口径：本月消费缺失时
 * 保持空串，绝不把累计消费兜底进"本月用量"。
 *
 * 数据优先级（本次修正）：登录时 DOM 采集的快照无时间戳、永不刷新，若继续让它
 * 无条件优先于实时抓取，余额/用量会冻结在登录时刻却以"真实口径"展示。现改为
 * **实时 SSR 抓取优先**，快照仅在实时抓取失败或未解析出余额时回退使用，且回退结果
 * 强制 `isStale = true`（卡片显示"缓存"药丸、不进入余额趋势、不进 freshOnly 汇总），
 * `dataSourceLabel = "登录时快照"` 标明时效。
 *
 * 失败语义（本次修正）：控制台抓取失败不再一律置 `needsRelogin = true`——只有
 * 会话失效（HTTP 401/403）或缺少凭据时才提示重登；网络异常/限流/页面结构变更时
 * 会话仍然有效，重登解决不了问题。
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

    /** 无 API Key 但有网页会话：实时 SSR 抓取优先，失败时回退登录时快照并如实标注时效。 */
    private suspend fun fetchConsoleSession(): QuotaInfo {
        val hasSession = runCatching { webSessionStore.hasWebSession(platform) }.getOrDefault(false)
        if (!hasSession) return QuotaInfo.notConfigured(platform)

        val cookie = runCatching { webSessionStore.getWebSessionCookie(platform) }.getOrNull()

        // 1) 实时抓取优先：SSR HTML 解析出余额即视为最新数据
        val live = cookie?.takeIf { it.isNotBlank() }?.let { fetchConsoleHtml(it) }
        if (live is ConsoleOutcome.Success && live.data.balance != null) {
            val data = live.data
            return QuotaInfo(
                platform = platform,
                isAvailable = true,
                isConfigured = true,
                totalBalance = ProviderSupport.fmtAmount(data.balance),
                monthlyUsage = data.monthCost?.let { ProviderSupport.fmtAmount(it) } ?: "",
                monthlyUsageSource = if (data.monthCost != null) MonthlyUsageSource.SERVER else null,
                currency = "CNY",
                dataSourceLabel = "网页控制台",
                modelUsages = emptyList(),
                boosterInfo = data.totalCost?.let { "累计消费 ¥${ProviderSupport.fmtAmount(it)}" }
            )
        }

        // 2) 实时抓取失败或未解析出余额：回退登录时快照。快照无时间戳，isStale = true
        //    阻止其进入余额趋势与 freshOnly 汇总，卡片显示"缓存"药丸 + 快照来源标注
        val snapshot = loadSnapshot()
        if (snapshot?.balance != null) {
            return QuotaInfo(
                platform = platform,
                isAvailable = true,
                isConfigured = true,
                totalBalance = snapshot.balance,
                monthlyUsage = snapshot.monthUsage.orEmpty(),
                monthlyUsageSource = if (snapshot.monthUsage != null) MonthlyUsageSource.SERVER else null,
                currency = "CNY",
                isStale = true,
                dataSourceLabel = "登录时快照",
                modelUsages = emptyList(),
                boosterInfo = snapshot.boosterLabel(),
                consoleDiag = liveFailureDiag(live)
            )
        }

        // 3) 无可信余额可展示：如实报错。只有会话失效（401/403）或缺少凭据才提示重登
        return when (live) {
            is ConsoleOutcome.Failure -> QuotaInfo.error(platform, live.message)
                .copy(needsRelogin = live.authFailure, consoleDiag = live.diag)
            is ConsoleOutcome.Success -> QuotaInfo.error(platform, "未能从控制台解析到余额，页面结构可能已变更")
                .copy(consoleDiag = "实时解析部分成功但无余额")
            null -> QuotaInfo.error(platform, "网页会话凭据不完整，请重新登录获取")
                .copy(needsRelogin = true, consoleDiag = "缺少会话 Cookie")
        }
    }

    /** 快照卡片附带的原因说明：实时抓取为何失败/为何回退快照。 */
    private fun liveFailureDiag(live: ConsoleOutcome?): String = when (live) {
        null -> "无会话 Cookie，无法实时抓取"
        is ConsoleOutcome.Failure -> listOfNotNull(live.diag, "已回退登录时快照").joinToString("，")
        is ConsoleOutcome.Success -> "实时解析未取得余额，已回退登录时快照"
    }

    /** 登录时采集的控制台快照；三项全空视为无快照。余额可能未采集到（SPA 异步渲染），保持 null。 */
    private suspend fun loadSnapshot(): Snapshot? {
        val snap = runCatching { webSessionStore.getKimiSnapshot() }.getOrNull() ?: return null
        if (snap.balance == null && snap.month == null && snap.total == null) return null
        return Snapshot(
            balance = snap.balance?.let { ProviderSupport.fmtAmount(it) },
            monthUsage = snap.month?.let { ProviderSupport.fmtAmount(it) },
            totalCost = snap.total
        )
    }

    private sealed interface ConsoleOutcome {
        data class Success(val data: KimiConsoleParser.Result) : ConsoleOutcome

        data class Failure(
            val message: String,
            val diag: String?,
            /** true 仅当失败源于会话失效（401/403）；网络异常/限流/结构变更不应让用户重登 */
            val authFailure: Boolean = false
        ) : ConsoleOutcome
    }

    /** SSR HTML 抓取与解析，把"网络/HTTP 失败"与"解析不出数据"区分为两种失败语义。 */
    private suspend fun fetchConsoleHtml(cookie: String): ConsoleOutcome {
        val response = runCatching { kimiConsoleApi.getConsoleHome(cookie) }.getOrNull()
            ?: return ConsoleOutcome.Failure("控制台请求失败，请检查网络后重试", "控制台网络异常")
        if (!response.isSuccessful) {
            val authFailure = response.code() == 401 || response.code() == 403
            return ConsoleOutcome.Failure(
                message = if (authFailure) {
                    "网页会话已过期，请重新登录"
                } else {
                    "控制台请求被拒绝（HTTP ${response.code()}），请稍后重试"
                },
                diag = "控制台 HTTP ${response.code()}",
                authFailure = authFailure
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

    /** 快照的内部形态：余额/本月消费已格式化（登录时可能未采集到余额，故可空）；累计消费单独保留，不再冒充本月用量。 */
    private data class Snapshot(
        val balance: String?,
        val monthUsage: String?,
        val totalCost: String?
    ) {
        /** 累计消费展示文案（仅作附加信息，与"本月用量"分开呈现）。 */
        fun boosterLabel(): String? =
            totalCost?.let { "累计消费 ¥${ProviderSupport.fmtAmount(it)}" }
    }
}
