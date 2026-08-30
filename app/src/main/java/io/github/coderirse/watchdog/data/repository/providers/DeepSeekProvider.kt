package io.github.coderirse.watchdog.data.repository.providers

import io.github.coderirse.watchdog.BuildConfig
import io.github.coderirse.watchdog.data.api.DeepSeekApi
import io.github.coderirse.watchdog.data.api.DeepSeekConsoleApi
import io.github.coderirse.watchdog.data.api.DeepSeekConsoleParser
import io.github.coderirse.watchdog.data.local.SettingsStore
import io.github.coderirse.watchdog.data.local.WebSessionStore
import io.github.coderirse.watchdog.data.model.DailyModelUsage
import io.github.coderirse.watchdog.data.model.DailyUsage
import io.github.coderirse.watchdog.data.model.ModelUsage
import io.github.coderirse.watchdog.data.model.PlatformType
import io.github.coderirse.watchdog.data.model.QuotaInfo
import io.github.coderirse.watchdog.data.repository.PlatformQuotaProvider
import io.github.coderirse.watchdog.data.repository.ProviderSupport
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.Calendar
import java.util.Locale

/**
 * DeepSeek：余额（官方接口 /user/balance）+ 可选网页控制台真实用量明细。
 *
 * 数据源优先级：
 * 1. 有 API Key：官方余额接口；若另配置了网页会话，叠加控制台真实用量
 *    （按模型/按天明细 + 本月真实消耗；余额仍以官方接口为准，二者一致）。
 *    控制台抓取失败（会话过期/WAF/接口变更）时静默回退官方模式并标注诊断码。
 * 2. 无 API Key 但有网页会话：余额与用量全部来自控制台（console-only）。
 * 3. 两者皆无：未配置。
 *
 * 官方-only 模式的本月用量走本地月度估算（增量累计，见 SettingsStore）。
 */
class DeepSeekProvider(
    private val deepSeekApi: DeepSeekApi,
    private val deepSeekConsoleApi: DeepSeekConsoleApi,
    private val webSessionStore: WebSessionStore,
    private val settingsStore: SettingsStore
) : PlatformQuotaProvider {

    override val platform = PlatformType.DEEPSEEK

    override suspend fun fetch(): QuotaInfo {
        val apiKey = settingsStore.getApiKey(platform)
        val raw = if (apiKey != null) {
            fetchOfficial("Bearer $apiKey")
        } else {
            fetchConsoleOnly()
        }
        // 官方-only（无控制台数据）时叠加本地月度估算
        return if (raw.isAvailable && raw.errorMessage == null && raw.dataSourceLabel == null) {
            val balance = raw.totalBalance.toDoubleOrNull()
            if (balance != null) {
                val usage = settingsStore.recordBalanceAndGetMonthlyUsage(platform, balance)
                raw.copy(monthlyUsage = ProviderSupport.fmtUsage(usage))
            } else raw
        } else raw
    }

    private suspend fun fetchOfficial(authHeader: String): QuotaInfo {
        val response = deepSeekApi.getBalance(authHeader)
        if (!response.isSuccessful) return ProviderSupport.httpError(platform, response.code())

        val body = response.body()
        val balance = body?.balanceInfos?.firstOrNull()
        val base = QuotaInfo(
            platform = platform,
            isAvailable = body?.isAvailable ?: false,
            isConfigured = true,
            totalBalance = balance?.totalBalance ?: "0.00",
            currency = balance?.currency ?: "CNY"
        )

        // 可选增强：使用 WebLoginActivity 抓取的网页会话（userToken + Cookie）读取控制台
        // 真实用量明细。会话缺失时保持官方模式。
        val session = runCatching { webSessionStore.getWebSession(platform) }.getOrNull()
        if (session.isNullOrBlank()) {
            if (BuildConfig.DEBUG) android.util.Log.d("WatchDogRepo", "DeepSeek: no console session, official-only")
            return base
        }

        val cookie = runCatching { webSessionStore.getWebSessionCookie(platform) }.getOrNull()
        val console = runCatching { fetchConsole(session, cookie) }.getOrNull()
        // 已配置会话但控制台抓取失败：回退官方模式，并在卡片标注诊断码
        // （区分 WAF 拦截 429 / 接口变更 404 / 会话失效 401，便于定位）
        val consoleData = console?.data
            ?: return base.copy(
                consoleDiag = "控制台抓取失败 HTTP " +
                    (console?.codes?.takeIf { it.isNotEmpty() }?.joinToString("/") ?: "网络异常")
            )

        val wallet = consoleData.summary?.primaryBalance
        val currency = wallet?.currency?.takeIf { it.isNotBlank() } ?: base.currency
        return base.copy(
            totalBalance = wallet?.let { ProviderSupport.fmtAmount(it.balance) } ?: base.totalBalance,
            currency = currency,
            monthlyUsage = consoleData.monthlyUsage?.let { ProviderSupport.fmtUsage(it) } ?: base.monthlyUsage,
            modelUsages = consoleData.modelUsages,
            dailyUsage = consoleData.dailyUsage,
            dailyModelUsage = consoleData.dailyModelUsage,
            dataSourceLabel = "网页控制台"
        )
    }

    private data class ConsoleData(
        val summary: DeepSeekConsoleParser.UserSummary?,
        val monthlyUsage: Double?,
        val modelUsages: List<ModelUsage>,
        val dailyUsage: List<DailyUsage> = emptyList(),
        val dailyModelUsage: List<DailyModelUsage> = emptyList()
    )

    private data class ConsoleResult(
        val data: ConsoleData?,
        /** 三路请求的 HTTP 状态码（summary/cost/amount 顺序），诊断用。 */
        val codes: List<Int>
    )

    /** DeepSeek 无 API Key 时的控制台-only 数据源；会话缺失/失效则回退 notConfigured。 */
    private suspend fun fetchConsoleOnly(): QuotaInfo {
        val session = runCatching { webSessionStore.getWebSession(platform) }.getOrNull()
        if (session.isNullOrBlank()) return QuotaInfo.notConfigured(platform)
        val cookie = runCatching { webSessionStore.getWebSessionCookie(platform) }.getOrNull()
        val console = runCatching { fetchConsole(session, cookie) }.getOrNull()
        val data = console?.data ?: return QuotaInfo.notConfigured(platform)
        val wallet = data.summary?.primaryBalance
        val currency = wallet?.currency?.takeIf { it.isNotBlank() } ?: "CNY"
        return QuotaInfo(
            platform = platform,
            isAvailable = wallet != null,
            isConfigured = true,
            totalBalance = wallet?.let { ProviderSupport.fmtAmount(it.balance) } ?: "0.00",
            currency = currency,
            monthlyUsage = data.monthlyUsage?.let { ProviderSupport.fmtUsage(it) } ?: "0.00",
            modelUsages = data.modelUsages,
            dailyUsage = data.dailyUsage,
            dailyModelUsage = data.dailyModelUsage,
            dataSourceLabel = "网页控制台"
        )
    }

    /**
     * 拉取 DeepSeek 控制台数据：用户汇总 + 本月按模型成本 + 本月按模型 Token/请求数（三路并行）。
     * 用量走月度端点（usage/cost|amount?month=&year=）；旧版 by_api_key 端点已失效。
     * 请求携带登录时的 Cookie（WAF 指纹关联），解析不到任何数据时返回 data=null + 各路 HTTP 码。
     */
    private suspend fun fetchConsole(session: String, cookie: String?): ConsoleResult {
        val authHeader = "Bearer $session"
        val cal = Calendar.getInstance()
        val month = cal.get(Calendar.MONTH) + 1
        val year = cal.get(Calendar.YEAR)

        val (summaryResp, costResp, amountResp) = coroutineScope {
            val s = async { runCatching { deepSeekConsoleApi.getUserSummary(authHeader, cookie) }.getOrNull() }
            val c = async {
                runCatching { deepSeekConsoleApi.getUsageCostMonthly(authHeader, cookie, month, year) }.getOrNull()
            }
            val a = async {
                runCatching { deepSeekConsoleApi.getUsageAmountMonthly(authHeader, cookie, month, year) }.getOrNull()
            }
            Triple(s.await(), c.await(), a.await())
        }
        val codes = listOf(summaryResp, costResp, amountResp).mapNotNull { it?.code() }
        // 先读出响应体再解析：body 只能消费一次；诊断日志仅 debug 构建输出
        // （响应体含账户余额/用量等私有数据，release 严禁写入 logcat）
        val summaryBody = summaryResp?.takeIf { it.isSuccessful }?.body()?.string()
        val costBody = costResp?.takeIf { it.isSuccessful }?.body()?.string()
        val amountBody = amountResp?.takeIf { it.isSuccessful }?.body()?.string()
        if (BuildConfig.DEBUG) {
            android.util.Log.i(
                "WatchDogRepo",
                "DeepSeek console codes=$codes session=${session.length}c cookie=${cookie?.length ?: 0}c month=$month/$year"
            )
            listOf("summary" to summaryBody, "cost" to costBody, "amount" to amountBody)
                .forEach { (name, body) ->
                    android.util.Log.i(
                        "WatchDogRepo",
                        "DeepSeek console[$name] len=${body?.length ?: -1} body=${body?.take(400)?.replace('\n', ' ') ?: "<empty>"}"
                    )
                }
        }

        val summary = summaryBody?.let(DeepSeekConsoleParser::parseUserSummary)
        val costRows = costBody?.let { DeepSeekConsoleParser.parseMonthlyTotals(it, costMode = true) }
        val amountRows = amountBody?.let { DeepSeekConsoleParser.parseMonthlyTotals(it, costMode = false) }
        val modelUsages = buildMonthlyModelUsages(costRows, amountRows)
        // 按天消耗：amount 端点提供 token/请求，cost 端点提供金额，按 date 合并
        val dayAmount = amountBody?.let { DeepSeekConsoleParser.parseMonthlyDays(it, costMode = false) }
        val dayCost = costBody?.let { DeepSeekConsoleParser.parseMonthlyDays(it, costMode = true) }
        val dailyUsage = buildDailyUsage(dayAmount, dayCost)
        // 按模型 × 按天：amount/cost 端点 days[].data[] 每项带 model（真机实测）
        val dayModelAmount = amountBody?.let { DeepSeekConsoleParser.parseMonthlyDaysByModel(it, costMode = false) }
        val dayModelCost = costBody?.let { DeepSeekConsoleParser.parseMonthlyDaysByModel(it, costMode = true) }
        val dailyModelUsage = buildDailyModelUsage(dayModelAmount, dayModelCost)
        if (BuildConfig.DEBUG) {
            android.util.Log.i(
                "WatchDogRepo",
                "DeepSeek console parsed: summary=${summary != null} costRows=${costRows?.size} " +
                    "amountRows=${amountRows?.size} models=${modelUsages.size} days=${dailyUsage.size} " +
                    "modelDays=${dailyModelUsage.size}"
            )
        }

        if (summary == null && costRows == null && amountRows == null) {
            return ConsoleResult(null, codes)
        }

        // 真实本月用量：优先 summary.monthly_usage；缺失时按月度成本接口的模型金额合计
        val monthlyUsage = summary?.monthlyUsage
            ?: costRows?.sumOf { it.cost }?.takeIf { it > 0.0 }

        return ConsoleResult(
            ConsoleData(
                summary = summary,
                monthlyUsage = monthlyUsage,
                modelUsages = modelUsages,
                dailyUsage = dailyUsage,
                dailyModelUsage = dailyModelUsage
            ),
            codes
        )
    }

    /** 合并 amount/cost 按模型×按天数据为 DailyModelUsage 列表（按 date+model 对齐）。 */
    private fun buildDailyModelUsage(
        amountRows: List<DeepSeekConsoleParser.DailyModelRow>?,
        costRows: List<DeepSeekConsoleParser.DailyModelRow>?
    ): List<DailyModelUsage> {
        val costByKey = costRows?.associate { "${it.date}|${it.model}" to it.cost } ?: emptyMap()
        val keys = (amountRows?.map { "${it.date}|${it.model}" } ?: emptyList()).distinct().sorted()
        return keys.map { key ->
            val sep = key.indexOf('|')
            val date = key.substring(0, sep)
            val model = key.substring(sep + 1)
            val a = amountRows?.firstOrNull { it.date == date && it.model == model }
            DailyModelUsage(
                date = date,
                platform = platform,
                model = model,
                totalTokens = a?.totalTokens ?: 0,
                inputTokens = a?.inputTokens ?: 0,
                outputTokens = a?.outputTokens ?: 0,
                requests = a?.requests ?: 0,
                cost = costByKey[key] ?: 0.0
            )
        }
    }

    /** 合并 amount/cost 按天数据为 DailyUsage 列表（按 date 对齐，token 来自 amount、成本来自 cost）。 */
    private fun buildDailyUsage(
        amountDays: List<DeepSeekConsoleParser.DailyRow>?,
        costDays: List<DeepSeekConsoleParser.DailyRow>?
    ): List<DailyUsage> {
        val costByDate = costDays?.associate { it.date to it.cost } ?: emptyMap()
        val dates = (amountDays?.map { it.date } ?: emptyList()).distinct().sorted()
        return dates.map { date ->
            val a = amountDays?.firstOrNull { it.date == date }
            DailyUsage(
                date = date,
                platform = platform,
                totalTokens = a?.totalTokens ?: 0,
                inputTokens = a?.inputTokens ?: 0,
                outputTokens = a?.outputTokens ?: 0,
                requests = a?.requests ?: 0,
                cost = costByDate[date] ?: 0.0
            )
        }
    }

    /** 合并月度 cost/amount 接口为按模型明细：amount 提供 Token/请求数，cost 提供金额。 */
    private fun buildMonthlyModelUsages(
        costRows: List<DeepSeekConsoleParser.MonthlyRow>?,
        amountRows: List<DeepSeekConsoleParser.MonthlyRow>?
    ): List<ModelUsage> {
        val costByModel = costRows?.associate { it.model to it.cost } ?: emptyMap()
        val models = (costByModel.keys + (amountRows?.map { it.model } ?: emptyList())).distinct().sorted()
        return models.mapNotNull { model ->
            val a = amountRows?.firstOrNull { it.model == model }
            val inputTokens = (a?.promptTokens ?: 0) + (a?.cacheHit ?: 0) + (a?.cacheMiss ?: 0)
            val outputTokens = a?.outputTokens ?: 0
            val cost = costByModel[model] ?: 0.0
            if ((a?.requests ?: 0L) <= 0L && inputTokens + outputTokens <= 0L && cost <= 0.0) {
                return@mapNotNull null
            }
            ModelUsage(
                modelName = model,
                requestCount = a?.requests ?: 0,
                totalTokens = inputTokens + outputTokens,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                cost = String.format(Locale.US, "%.2f", cost)
            )
        }
    }
}
