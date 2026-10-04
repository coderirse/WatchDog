package io.github.coderirse.watchdog.data.repository.providers

import io.github.coderirse.watchdog.BuildConfig
import io.github.coderirse.watchdog.data.api.DeepSeekApi
import io.github.coderirse.watchdog.data.api.DeepSeekConsoleApi
import io.github.coderirse.watchdog.data.api.DeepSeekConsoleParser
import io.github.coderirse.watchdog.data.local.SettingsStore
import io.github.coderirse.watchdog.data.model.DailyModelUsage
import io.github.coderirse.watchdog.data.model.DailyUsage
import io.github.coderirse.watchdog.data.model.ModelUsage
import io.github.coderirse.watchdog.data.model.MonthlyUsageSource
import io.github.coderirse.watchdog.data.model.PlatformType
import io.github.coderirse.watchdog.data.model.QuotaInfo
import io.github.coderirse.watchdog.data.repository.PlatformQuotaProvider
import io.github.coderirse.watchdog.data.repository.ProviderSupport
import io.github.coderirse.watchdog.data.repository.WebSessionAccess
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
    private val webSessionAccess: WebSessionAccess,
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
        return enrichMonthlyUsage(raw)
    }

    /**
     * "本月用量"缺失时用本地增量累计补齐并标注估算口径。
     * 旧实现只在 dataSourceLabel == null 时执行，而所有控制台路径都带标签，该分支
     * 从未可达：控制台路径缺用量时被渲染成假 0（SERVER 口径），console-only 路径
     * 标着 LOCAL_ESTIMATE 却从未计算。余额不可解析时保持未知（空串占位），不造假 0。
     */
    private suspend fun enrichMonthlyUsage(raw: QuotaInfo): QuotaInfo {
        if (!raw.isAvailable || raw.errorMessage != null) return raw
        if (raw.monthlyUsage.isNotBlank()) return raw  // 控制台真实用量，保持 SERVER 口径
        val balance = raw.totalBalance.toDoubleOrNull() ?: return raw
        val usage = settingsStore.recordBalanceAndGetMonthlyUsage(platform, balance)
        return raw.copy(
            monthlyUsage = ProviderSupport.fmtUsage(usage),
            monthlyUsageSource = MonthlyUsageSource.LOCAL_ESTIMATE
        )
    }

    private suspend fun fetchOfficial(authHeader: String): QuotaInfo {
        val response = deepSeekApi.getBalance(authHeader)
        if (!response.isSuccessful) return ProviderSupport.httpError(platform, response.code())

        val body = response.body() ?: return ProviderSupport.emptyResponse(platform)
        val balance = body.balanceInfos?.firstOrNull()
        // 官方接口返回 is_available=true 但余额字段缺失时不冒充 0.00：
        // 假 0 会污染总额汇总并让卡片误判为"正常"，故如实报错
        if (body.isAvailable && balance?.totalBalance.isNullOrBlank()) {
            return QuotaInfo.error(platform, "官方接口未返回余额字段，请稍后重试")
        }
        val base = QuotaInfo(
            platform = platform,
            isAvailable = body.isAvailable,
            isConfigured = true,
            totalBalance = balance?.totalBalance ?: "",
            currency = balance?.currency ?: "CNY",
            // "本月用量"留给 enrichMonthlyUsage 补齐：官方接口不提供，未知时不得渲染成 "0"
            monthlyUsage = "",
            monthlyUsageSource = null
        )

        // 可选增强：使用 WebLoginActivity 抓取的网页会话（userToken + Cookie）读取控制台
        // 真实用量明细。会话缺失时保持官方模式。
        val session = runCatching { webSessionAccess.getWebSession(platform) }.getOrNull()
        if (session.isNullOrBlank()) {
            if (BuildConfig.DEBUG) android.util.Log.d("WatchDogRepo", "DeepSeek: no console session, official-only")
            return base
        }

        val cookie = runCatching { webSessionAccess.getWebSessionCookie(platform) }.getOrNull()
        val console = runCatching { fetchConsole(session, cookie) }.getOrNull()
        // 已配置会话但控制台抓取失败：回退官方模式，并在卡片标注诊断码
        // （区分 WAF 拦截 429 / 接口变更 404 / 会话失效 401，便于定位）
        val consoleData = console?.data
            ?: return base.copy(
                consoleDiag = consoleFailureDiag(console?.codes.orEmpty())
            )

        val wallet = consoleData.summary?.primaryBalance
        val currency = wallet?.currency?.takeIf { it.isNotBlank() } ?: base.currency
        return base.copy(
            totalBalance = wallet?.let { ProviderSupport.fmtAmount(it.balance) } ?: base.totalBalance,
            currency = currency,
            // 控制台有真实本月用量时以其为准（SERVER 口径）；缺失时保持空串未知，
            // 由 enrichMonthlyUsage 用本地增量累计补齐并标注估算
            monthlyUsage = consoleData.monthlyUsage?.let { ProviderSupport.fmtUsage(it) } ?: "",
            monthlyUsageSource = if (consoleData.monthlyUsage != null) MonthlyUsageSource.SERVER else null,
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

    /** DeepSeek 无 API Key 时的控制台-only 数据源；会话缺失返回未配置，抓取失败如实报错。 */
    private suspend fun fetchConsoleOnly(): QuotaInfo {
        val session = runCatching { webSessionAccess.getWebSession(platform) }.getOrNull()
        if (session.isNullOrBlank()) return QuotaInfo.notConfigured(platform)
        val cookie = runCatching { webSessionAccess.getWebSessionCookie(platform) }.getOrNull()
        val console = runCatching { fetchConsole(session, cookie) }.getOrNull()

        val codes = console?.codes.orEmpty()
        val data = console?.data
            ?: return QuotaInfo
                .error(platform, consoleFailureMessage(codes))
                .copy(
                    // 仅会话失效（401/403）才提示重登：网络故障/限流/接口变更时会话仍有效，
                    // 重登无济于事（旧实现一律置 true，与失败文案自相矛盾）
                    needsRelogin = codes.any { it == 401 || it == 403 },
                    consoleDiag = consoleFailureDiag(codes)
                )

        val wallet = data.summary?.primaryBalance
            ?: return QuotaInfo
                .error(platform, "控制台未返回余额信息，接口可能已变更")
                .copy(consoleDiag = "用户汇总解析无余额字段")

        return QuotaInfo(
            platform = platform,
            isAvailable = true,
            isConfigured = true,
            totalBalance = ProviderSupport.fmtAmount(wallet.balance),
            currency = wallet.currency?.takeIf { it.isNotBlank() } ?: "CNY",
            monthlyUsage = data.monthlyUsage?.let { ProviderSupport.fmtUsage(it) } ?: "",
            monthlyUsageSource = if (data.monthlyUsage != null) {
                MonthlyUsageSource.SERVER
            } else {
                null  // 未知口径留给 enrichMonthlyUsage 以本地估算补齐，不再虚标估算
            },
            modelUsages = data.modelUsages,
            dailyUsage = data.dailyUsage,
            dailyModelUsage = data.dailyModelUsage,
            dataSourceLabel = "网页控制台"
        )
    }

    /** 控制台抓取失败的诊断码（保留原始 HTTP 码，便于区分 WAF/接口变更/会话失效）。 */
    private fun consoleFailureDiag(codes: List<Int>): String =
        "控制台抓取失败 HTTP " + codes.takeIf { it.isNotEmpty() }?.joinToString("/").orEmpty()

    /** 按三路 HTTP 码给出可读失败原因（用户可见文案，不再直接把状态码丢给用户）。 */
    private fun consoleFailureMessage(codes: List<Int>): String = when {
        codes.isEmpty() -> "控制台连接失败，请检查网络后重试"
        codes.any { it == 401 || it == 403 } -> "网页会话已过期，请重新登录"
        codes.any { it == 429 } -> "控制台请求过于频繁被拦截，请稍后重试"
        codes.all { it == 200 } -> "控制台响应格式无法识别，接口可能已变更"
        else -> "控制台读取失败（HTTP ${codes.joinToString("/")}），请稍后重试"
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
        // 先读出响应体再解析：body 只能消费一次。单路读体失败（中途断连）只弃该路，
        // 不拖垮其余两路已成功的数据与诊断码（旧实现任一路抛 IOException 会丢弃全部）。
        // 非成功响应关闭错误体释放连接。响应体含账户私有数据，诊断日志仅 debug 输出
        val summaryBody = readSuccessBody(summaryResp)
        val costBody = readSuccessBody(costResp)
        val amountBody = readSuccessBody(amountResp)
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

    /**
     * 合并 amount/cost 按模型×按天数据为 DailyModelUsage 列表（按 date+model 对齐）。
     * 键取两侧并集：cost 端点单独失败/被拦截时其行不再凭空消失；cost 缺失记为 null
     * （未知），不得兜底 0.00 渲染成"免费"。
     */
    private fun buildDailyModelUsage(
        amountRows: List<DeepSeekConsoleParser.DailyModelRow>?,
        costRows: List<DeepSeekConsoleParser.DailyModelRow>?
    ): List<DailyModelUsage> {
        val costByKey = costRows?.associate { "${it.date}|${it.model}" to it.cost } ?: emptyMap()
        val keys = ((amountRows?.map { "${it.date}|${it.model}" } ?: emptyList()) +
            (costRows?.map { "${it.date}|${it.model}" } ?: emptyList())).distinct().sorted()
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
                cost = costByKey[key]
            )
        }
    }

    /** 合并 amount/cost 按天数据为 DailyUsage 列表（按 date 对齐，token 来自 amount、成本来自 cost）。 */
    private fun buildDailyUsage(
        amountDays: List<DeepSeekConsoleParser.DailyRow>?,
        costDays: List<DeepSeekConsoleParser.DailyRow>?
    ): List<DailyUsage> {
        val costByDate = costDays?.associate { it.date to it.cost } ?: emptyMap()
        val dates = ((amountDays?.map { it.date } ?: emptyList()) +
            (costDays?.map { it.date } ?: emptyList())).distinct().sorted()
        return dates.map { date ->
            val a = amountDays?.firstOrNull { it.date == date }
            DailyUsage(
                date = date,
                platform = platform,
                totalTokens = a?.totalTokens ?: 0,
                inputTokens = a?.inputTokens ?: 0,
                outputTokens = a?.outputTokens ?: 0,
                requests = a?.requests ?: 0,
                cost = costByDate[date]
            )
        }
    }

    /** 合并月度 cost/amount 接口为按模型明细：amount 提供 Token/请求数，cost 提供金额。 */
    private fun buildMonthlyModelUsages(
        costRows: List<DeepSeekConsoleParser.MonthlyRow>?,
        amountRows: List<DeepSeekConsoleParser.MonthlyRow>?
    ): List<ModelUsage> {
        val models = ((costRows?.map { it.model } ?: emptyList()) +
            (amountRows?.map { it.model } ?: emptyList())).distinct().sorted()
        return models.mapNotNull { model ->
            val a = amountRows?.firstOrNull { it.model == model }
            val c = costRows?.firstOrNull { it.model == model }
            val inputTokens = (a?.promptTokens ?: 0) + (a?.cacheHit ?: 0) + (a?.cacheMiss ?: 0)
            val outputTokens = a?.outputTokens ?: 0
            val cost = c?.cost ?: 0.0
            // 请求数两侧都可携带（解析器两种模式都累计 REQUEST）：amount 端点单独失败
            // 时不再把请求数显示成 0
            val requests = a?.requests ?: c?.requests ?: 0L
            if (requests <= 0L && inputTokens + outputTokens <= 0L && cost <= 0.0) {
                return@mapNotNull null
            }
            ModelUsage(
                modelName = model,
                requestCount = requests,
                totalTokens = inputTokens + outputTokens,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                cost = String.format(Locale.US, "%.2f", cost)
            )
        }
    }

    /** 读成功响应体；读体失败（中途断连）返回 null 而不向上抛。非成功响应关闭错误体释放连接。 */
    private fun readSuccessBody(response: retrofit2.Response<okhttp3.ResponseBody>?): String? {
        response ?: return null
        return try {
            if (response.isSuccessful) response.body()?.string() else {
                response.errorBody()?.close()
                null
            }
        } catch (e: java.io.IOException) {
            null
        }
    }
}
