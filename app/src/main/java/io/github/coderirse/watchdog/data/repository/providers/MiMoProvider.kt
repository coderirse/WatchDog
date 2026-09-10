package io.github.coderirse.watchdog.data.repository.providers

import io.github.coderirse.watchdog.data.api.MiMoConsoleApi
import io.github.coderirse.watchdog.data.api.MiMoConsoleParser
import io.github.coderirse.watchdog.data.local.SettingsStore
import io.github.coderirse.watchdog.data.model.PlatformType
import io.github.coderirse.watchdog.data.model.QuotaInfo
import io.github.coderirse.watchdog.data.repository.PlatformQuotaProvider
import io.github.coderirse.watchdog.data.repository.WebSessionAccess
import io.github.coderirse.watchdog.util.FormatUtils
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.Locale

/**
 * 小米 MiMo：官方无"仅凭 API Key"的余额/用量接口（2026-02 实测全部 404/401），
 * 数据来自网页控制台内部接口（platform.xiaomimimo.com 的 /api/v1 路径），
 * 鉴权头 api-platform_ph = 浏览器 Cookie 中的会话值（WebLoginActivity 抓取或用户手动粘贴）。
 *
 * 依赖 [WebSessionAccess] 窄接口而非 WebSessionStore 具体类，便于纯 JVM 测试。
 */
class MiMoProvider(
    private val mimoConsoleApi: MiMoConsoleApi,
    private val webSessionAccess: WebSessionAccess,
    private val settingsStore: SettingsStore
) : PlatformQuotaProvider {

    override val platform = PlatformType.MIMO

    override suspend fun fetch(): QuotaInfo {
        val session = webSessionAccess.getWebSession(platform)
            // 无会话：引导进入内嵌登录页（WebView 登录态持久化，会话仍有效时秒抓凭证免输入）
            ?: return QuotaInfo.error(platform, "尚未建立网页会话，点击下方按钮打开登录页获取")
                .copy(needsRelogin = true)

        // 1) Token Plan 订阅数据（detail / usage 并行拉取，互不阻塞）
        val (detailResponse, usageResponse) = coroutineScope {
            val d = async { runCatching { mimoConsoleApi.getTokenPlanDetail(session) }.getOrNull() }
            val u = async { runCatching { mimoConsoleApi.getTokenPlanUsage(session) }.getOrNull() }
            d.await() to u.await()
        }

        val unauthorized = (detailResponse?.code() == 401) || (usageResponse?.code() == 401)
        val planDetail = detailResponse
            ?.takeIf { it.isSuccessful }
            ?.body()?.string()
            ?.let(MiMoConsoleParser::parseTokenPlanDetail)
        val windows = usageResponse
            ?.takeIf { it.isSuccessful }
            ?.body()?.string()
            ?.let(MiMoConsoleParser::parseTokenPlanUsage)
            ?: emptyList()

        // 2) 余额探测：优先用上次命中的路径，失效时候选路径逐个探测（带会话），
        //    第一个 200 且可解析出余额者胜。任何一步失败都不影响令牌套餐数据展示。
        val balance = if (!unauthorized) probeBalance(session) else null

        // 3) 组合结果：订阅数据存在（套餐/窗口）或余额命中即视为有效数据
        val hasSubscription = planDetail != null || windows.isNotEmpty()
        if (!hasSubscription && balance == null) {
            return when {
                // MiMo 走小米账号 OAuth（含人机验证），无法后台续期；官方 Cookie 有效期 24h，
                // 过期后引导用户进入登录页（WebView 会话仍有效时自动完成，无需再输账号）
                unauthorized ->
                    QuotaInfo.error(platform, "网页会话已过期，点击下方按钮重新登录")
                        .copy(needsRelogin = true)
                else ->
                    QuotaInfo.error(platform, "控制台响应格式无法识别，接口可能已变更")
            }
        }

        var result = QuotaInfo(
            platform = platform,
            isAvailable = hasSubscription || balance != null,
            isConfigured = true,
            planName = planDetail?.planName,
            quotaWindows = windows,
            boosterInfo = planDetail?.let { buildPlanDesc(it) },
            currency = if (hasSubscription && balance == null) "Credits" else "CNY",
            dataSourceLabel = "网页控制台"
        )

        if (balance != null) {
            // CNY 余额走本地月度追踪（与 DeepSeek/Kimi 相同机制），并参与总余额/趋势
            val currency = balance.currency ?: "CNY"
            val isCny = currency.equals("CNY", true)
            val monthlyUsage = if (isCny) {
                settingsStore.recordBalanceAndGetMonthlyUsage(platform, balance.balance)
            } else null
            result = result.copy(
                totalBalance = String.format(Locale.US, "%.2f", balance.balance),
                availableBalance = String.format(Locale.US, "%.2f", balance.balance),
                monthlyUsage = if (monthlyUsage != null && monthlyUsage >= 0.01)
                    String.format(Locale.US, "%.2f", monthlyUsage) else "0.00",
                currency = currency
            )
        }

        return result
    }

    /**
     * 带会话探测余额类候选路径；首个命中即返回并持久化该路径，
     * 下次刷新优先直用（避免每轮全量探测 8 个候选路径触发网关风控）。
     */
    private suspend fun probeBalance(session: String): MiMoConsoleParser.BalanceInfo? {
        // 1) 上次命中的路径优先
        val saved = runCatching { settingsStore.getProbePath(platform) }.getOrNull()
        if (saved != null) {
            val hit = probePath(session, saved)
            if (hit != null) return hit
            // 缓存路径失效（接口变更）→ 回退全量探测，探测成功后覆盖
        }
        // 2) 全量候选探测
        for (path in MiMoConsoleParser.BALANCE_CANDIDATES) {
            if (path == saved) continue
            val info = probePath(session, path) ?: continue
            runCatching { settingsStore.saveProbePath(platform, path) }
            return info
        }
        return null
    }

    private suspend fun probePath(session: String, path: String): MiMoConsoleParser.BalanceInfo? {
        val body = runCatching {
            val resp = mimoConsoleApi.getRaw(session, path)
            if (resp.isSuccessful) resp.body()?.string() else null
        }.getOrNull() ?: return null
        return MiMoConsoleParser.parseBalanceInfo(body)
    }

    /** 订阅详情 → booster 描述文案（纯字符串，展示在卡片底部）。 */
    private fun buildPlanDesc(d: MiMoConsoleParser.TokenPlanDetail): String? {
        val cycle = d.cycleLabel ?: return null
        val credits = d.totalCredits?.let { FormatUtils.formatNumber(it.toLong()) }
        return if (credits != null) "$cycle · Credits 额度 $credits" else cycle
    }
}
