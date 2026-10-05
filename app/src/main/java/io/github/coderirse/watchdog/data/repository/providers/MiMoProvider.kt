package io.github.coderirse.watchdog.data.repository.providers

import io.github.coderirse.watchdog.BuildConfig
import io.github.coderirse.watchdog.data.api.MiMoConsoleApi
import io.github.coderirse.watchdog.data.api.MiMoConsoleParser
import io.github.coderirse.watchdog.data.local.SettingsStore
import io.github.coderirse.watchdog.data.model.PlatformType
import io.github.coderirse.watchdog.data.model.QuotaInfo
import io.github.coderirse.watchdog.data.repository.PlatformQuotaProvider
import io.github.coderirse.watchdog.data.repository.WebSessionAccess
import io.github.coderirse.watchdog.util.DebugLog
import io.github.coderirse.watchdog.util.FormatUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.IOException
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
        // 整串浏览器 Cookie（登录时由 CookieManager 抓取并加密保存），
        // 与 api-platform_ph 一同发送——只带后者会被网关判为未登录（真机实测 401）
        val cookie = webSessionAccess.getWebSessionCookie(platform)

        // 1) Token Plan 订阅数据（detail / usage 并行拉取，互不阻塞）。
        // 网络异常不能吞成 null：两路全失败时向上抛给 QuotaRepository，由它回退缓存并
        // 给出正确的网络文案（旧实现吞掉后断网时误报"接口可能已变更"且绕过缓存回退）。
        // 单路失败只记下原因，不影响另一路；协程取消必须原样传播。
        var networkError: IOException? = null
        val (detailResponse, usageResponse) = coroutineScope {
            val d = async {
                try {
                    mimoConsoleApi.getTokenPlanDetail(session, cookie)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    networkError = e
                    null
                }
            }
            val u = async {
                try {
                    mimoConsoleApi.getTokenPlanUsage(session, cookie)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    networkError = e
                    null
                }
            }
            d.await() to u.await()
        }

        // 诊断：会话是否被服务端接受（只记长度，不记令牌内容）。
        // MiMo 无官方接口文档，401 的成因（Cookie 不完整/令牌形态/风控绑定/接口变更）
        // 只能靠这些日志区分；写入 DebugLog（落盘）以便设备掉线后仍能事后拉取
        if (BuildConfig.DEBUG) {
            DebugLog.i(
                TAG,
                "MiMo fetch: sessionLen=${session.length} cookieLen=${cookie?.length ?: 0} " +
                    "detail=${detailResponse?.code() ?: -1} usage=${usageResponse?.code() ?: -1}"
            )
            // 401 时把响应体片段写出来：网关的错误文案（如 invalid token / session expired）
            // 是判断"凭证不完整"还是"会话真的过期"的唯一依据
            listOf("detail" to detailResponse, "usage" to usageResponse).forEach { (name, resp) ->
                if (resp != null && resp.code() == 401) {
                    val body = runCatching { resp.errorBody()?.string() }.getOrNull()
                    DebugLog.i(TAG, "MiMo 401 [$name] body=${body?.take(300) ?: "<empty>"}")
                    val headers = resp.headers().names().joinToString(",")
                    DebugLog.i(TAG, "MiMo 401 [$name] respHeaders=$headers")
                }
            }
        } else {
            // release 构建不读诊断体：关闭未消费的错误体，避免连接被持有到 GC
            listOf(detailResponse, usageResponse).forEach { resp ->
                if (resp != null && !resp.isSuccessful) runCatching { resp.errorBody()?.close() }
            }
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
        val balance = if (!unauthorized) probeBalance(session, cookie) else null

        // 3) 组合结果：订阅数据存在（套餐/窗口）或余额命中即视为有效数据
        val hasSubscription = planDetail != null || windows.isNotEmpty()
        if (!hasSubscription && balance == null) {
            // 两路订阅请求都没收到响应（纯网络故障）：向上传播，让 QuotaRepository
            // 走"异常 → 缓存回退"机制并给出正确的网络文案，而不是误报"接口已变更"
            networkError?.let { throw it }
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

        if (BuildConfig.DEBUG) {
            // 余额是"探测候选路径"得来，必须能看出到底命中没有、命中的是哪条路径
            // （网页实际调用的是 /api/v1/balance，故该路径应命中）
            DebugLog.i(
                TAG,
                "MiMo balance probe: hit=${balance != null} path=${balance?.path} " +
                    "value=${balance?.info?.balance} currency=${balance?.info?.currency} " +
                    "savedPath=${runCatching { settingsStore.getProbePath(platform) }.getOrNull()}"
            )
        }

        if (balance != null) {
            // CNY 余额走本地月度追踪（与 DeepSeek/Kimi 相同机制），并参与总余额/趋势。
            // 信任条件：命中实测路径（/api/v1/balance，网页控制台实际调用的接口）或响应
            // 带显式 CNY 货币。其余候选路径未实测，可能返回无货币字段的 Credits 类数值——
            // 旧实现把它们默认当 CNY 写入持久化月度追踪并计入总额，属于脏数据来源；
            // 现改为仅展示（非 CNY 标签），不追踪、不进 CNY 汇总。
            val trustedCny = balance.path == VERIFIED_BALANCE_PATH ||
                balance.info.currency.equals("CNY", ignoreCase = true)
            val monthlyUsage = if (trustedCny) {
                settingsStore.recordBalanceAndGetMonthlyUsage(platform, balance.info.balance)
            } else null
            result = result.copy(
                totalBalance = String.format(Locale.US, "%.2f", balance.info.balance),
                availableBalance = String.format(Locale.US, "%.2f", balance.info.balance),
                monthlyUsage = when {
                    monthlyUsage != null && monthlyUsage >= 0.01 ->
                        String.format(Locale.US, "%.2f", monthlyUsage)
                    trustedCny -> "0.00"  // 已确认 CNY，追踪值为真 0
                    else -> ""            // 货币不明：显示未知占位符而非假 0
                },
                currency = if (trustedCny) "CNY" else (balance.info.currency ?: "Credits")
            )
        }

        return result
    }

    /**
     * 带会话探测余额类候选路径；首个命中即返回并持久化该路径，
     * 下次刷新优先直用（避免每轮全量探测 8 个候选路径触发网关风控）。
     */
    private suspend fun probeBalance(
        session: String,
        cookie: String?
    ): BalanceHit? {
        // 1) 上次命中的路径优先
        val saved = runCatching { settingsStore.getProbePath(platform) }.getOrNull()
        if (saved != null) {
            probePath(session, cookie, saved)?.let { return BalanceHit(it, saved) }
            // 缓存路径失效（接口变更）→ 回退全量探测，探测成功后覆盖
        }
        // 2) 全量候选探测
        for (path in MiMoConsoleParser.BALANCE_CANDIDATES) {
            if (path == saved) continue
            probePath(session, cookie, path)?.let {
                runCatching { settingsStore.saveProbePath(platform, path) }
                return BalanceHit(it, path)
            }
        }
        return null
    }

    /** 探测命中结果：数值 + 命中路径（路径决定该数值是否可信为 CNY 余额）。 */
    private data class BalanceHit(
        val info: MiMoConsoleParser.BalanceInfo,
        val path: String
    )

    private suspend fun probePath(
        session: String,
        cookie: String?,
        path: String
    ): MiMoConsoleParser.BalanceInfo? {
        val body = runCatching {
            val resp = mimoConsoleApi.getRaw(session, cookie, path)
            if (resp.isSuccessful) {
                resp.body()?.string()
            } else {
                // 未消费的错误体要关闭，释放底层连接
                runCatching { resp.errorBody()?.close() }
                null
            }
        }.getOrNull() ?: return null
        return MiMoConsoleParser.parseBalanceInfo(body)
    }

    /** 订阅详情 → booster 描述文案（纯字符串，展示在卡片底部）。 */
    private fun buildPlanDesc(d: MiMoConsoleParser.TokenPlanDetail): String? {
        val cycle = d.cycleLabel ?: return null
        val credits = d.totalCredits?.let { FormatUtils.formatNumber(it.toLong()) }
        return if (credits != null) "$cycle · Credits 额度 $credits" else cycle
    }

    private companion object {
        const val TAG = "WatchDogMiMo"

        /** 实测可用的余额路径（网页控制台实际调用的接口），作为余额数值的信任判定依据。 */
        const val VERIFIED_BALANCE_PATH = "/api/v1/balance"
    }
}
