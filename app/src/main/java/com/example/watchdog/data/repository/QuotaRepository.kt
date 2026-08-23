package com.example.watchdog.data.repository

import com.example.watchdog.data.api.DeepSeekApi
import com.example.watchdog.data.api.DeepSeekConsoleApi
import com.example.watchdog.data.api.DeepSeekConsoleParser
import com.example.watchdog.data.api.GlmApi
import com.example.watchdog.data.api.GlmCodingPlanApi
import com.example.watchdog.data.api.GlmCodingPlanParser
import com.example.watchdog.data.api.GlmTokenAccountsResponse
import com.example.watchdog.data.api.KimiApi
import com.example.watchdog.data.api.KimiCodeApi
import com.example.watchdog.data.api.KimiCodeParser
import com.example.watchdog.data.api.KimiConsoleApi
import com.example.watchdog.data.api.KimiConsoleParser
import com.example.watchdog.data.api.MiMoConsoleApi
import com.example.watchdog.data.api.MiMoConsoleParser
import com.example.watchdog.data.api.SiliconFlowApi
import com.example.watchdog.data.local.QuotaCacheStore
import com.example.watchdog.data.local.SettingsStore
import com.example.watchdog.data.local.WebSessionStore
import com.example.watchdog.data.model.ModelUsage
import com.example.watchdog.data.model.PlatformType
import com.example.watchdog.data.model.QuotaInfo
import com.example.watchdog.data.model.QuotaWindow
import com.example.watchdog.util.FormatUtils
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class QuotaRepository(
    private val settingsStore: SettingsStore,
    private val cacheStore: QuotaCacheStore,
    private val deepSeekApi: DeepSeekApi,
    private val kimiApi: KimiApi,
    private val glmApi: GlmApi,
    private val glmCodingPlanApi: GlmCodingPlanApi,
    private val siliconFlowApi: SiliconFlowApi,
    private val kimiCodeApi: KimiCodeApi,
    private val kimiConsoleApi: KimiConsoleApi,
    private val mimoConsoleApi: MiMoConsoleApi,
    private val deepSeekConsoleApi: DeepSeekConsoleApi,
    private val webSessionStore: WebSessionStore
) {
    suspend fun fetchAllQuotas(): List<QuotaInfo> = coroutineScope {
        val configuredPlatforms = settingsStore.getConfiguredPlatforms().toMutableSet()
        // 支持网页会话的平台：即使未填 API Key，只要配置了会话（能读控制台数据）也算已配置，
        // 否则登录了控制台但没填 API Key 的平台会整卡消失（真机实测：DeepSeek 登录后仪表盘空白）
        for (platform in PlatformType.entries) {
            if (platform.supportsConsoleSession &&
                runCatching { webSessionStore.hasWebSession(platform) }.getOrDefault(false)
            ) {
                configuredPlatforms.add(platform)
            }
        }
        val allPlatforms = PlatformType.entries

        allPlatforms.map { platform ->
            async {
                if (platform !in configuredPlatforms) {
                    QuotaInfo.notConfigured(platform)
                } else {
                    fetchPlatformQuota(platform)
                }
            }
        }.map { it.await() }
    }

    suspend fun fetchPlatformQuota(platform: PlatformType): QuotaInfo {
        return try {
            val apiKey = settingsStore.getApiKey(platform)
            val authHeader = apiKey?.let { "Bearer $it" }
            val rawQuota = when (platform) {
                PlatformType.DEEPSEEK ->
                    if (authHeader != null) fetchDeepSeek(authHeader, platform)
                    else fetchDeepSeekConsoleOnly(platform)
                PlatformType.KIMI ->
                    if (authHeader != null) fetchKimiBalance(authHeader, platform)
                    else fetchKimiConsoleSession(platform)
                PlatformType.GLM -> authHeader?.let { fetchGlmTokenAccounts(it, platform) }
                    ?: QuotaInfo.notConfigured(platform)
                PlatformType.SILICONFLOW -> authHeader?.let { fetchSiliconFlow(it, platform) }
                    ?: QuotaInfo.notConfigured(platform)
                PlatformType.VOLCENGINE_ARK -> fetchVolcengineArk(platform)
                PlatformType.KIMI_CODE -> authHeader?.let { fetchKimiCodeUsages(it, platform) }
                    ?: QuotaInfo.notConfigured(platform)
                PlatformType.MIMO -> fetchMimoConsole(platform)
            }

            // DeepSeek/Kimi 无官方用量API，用本地月初余额快照推算；
            // GLM 有资源包接口、SiliconFlow 有官方可用余额字段，不走本地推算；
            // 火山方舟的本地月度追踪在 fetchVolcengineArk 内部处理（初始余额来自用户输入），
            // Kimi Code 为订阅配额模式，均无远程余额，不参与此推算；
            // DeepSeek 配置了网页会话时已使用控制台真实用量（dataSourceLabel 非空），不再走本地推算；
            // MiMo 的 CNY 余额同样在 fetchMimoConsole 内部走本地月度追踪
            val result = if ((platform == PlatformType.DEEPSEEK || platform == PlatformType.KIMI)
                && rawQuota.isAvailable && rawQuota.errorMessage == null
                && rawQuota.dataSourceLabel == null
            ) {
                val balance = rawQuota.totalBalance.toDoubleOrNull()
                if (balance != null) {
                    val usage = settingsStore.recordBalanceAndGetMonthlyUsage(platform, balance)
                    rawQuota.copy(
                        monthlyUsage = if (usage < 0.01) "0.00" else String.format(Locale.US, "%.2f", usage)
                    )
                } else rawQuota
            } else rawQuota

            // 成功后写入缓存，供断网时回退展示
            if (result.errorMessage == null) {
                runCatching { cacheStore.put(platform, result) }
            }
            result
        } catch (e: Exception) {
            // 网络/解析异常时优先展示上次缓存的数据，并标记为缓存数据
            val cached = runCatching { cacheStore.get(platform) }.getOrNull()
            if (cached != null) {
                cached.copy(isConfigured = true, errorMessage = null, isStale = true)
            } else {
                val message = when (e) {
                    is java.net.UnknownHostException -> "无法连接服务器，请检查网络"
                    is java.net.SocketTimeoutException -> "请求超时，请稍后重试"
                    is java.io.IOException -> "网络请求失败"
                    else -> e.localizedMessage ?: "未知错误"
                }
                QuotaInfo.error(platform, message)
            }
        }
    }

    // ===== DeepSeek：余额（官方接口 /user/balance）+ 可选网页控制台真实用量明细 =====

    private suspend fun fetchDeepSeek(authHeader: String, platform: PlatformType): QuotaInfo {
        val response = deepSeekApi.getBalance(authHeader)
        if (!response.isSuccessful) return httpError(platform, response.code())

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
        // 真实用量明细——真实本月用量 + 按模型/请求数的调用明细（余额仍是官方接口，二者一致）。
        // 控制台请求失败（会话过期/WAF/接口变更）时静默回退官方 API Key 模式，
        // 不影响卡片展示；用户可从设置页重新打开登录页刷新会话（WebView 登录态持久化，
        // 会话仍有效时无需再次输入账号）。
        val session = runCatching { webSessionStore.getWebSession(platform) }.getOrNull()
        if (session.isNullOrBlank()) {
            android.util.Log.d("WatchDogRepo", "DeepSeek: no console session, official-only")
            return base
        }

        val cookie = runCatching { webSessionStore.getWebSessionCookie(platform) }.getOrNull()
        val console = runCatching { fetchDeepSeekConsole(session, cookie) }.getOrNull()
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
            totalBalance = wallet?.let { String.format(Locale.US, "%.2f", it.balance) } ?: base.totalBalance,
            currency = currency,
            monthlyUsage = consoleData.monthlyUsage?.let {
                if (it < 0.01) "0.00" else String.format(Locale.US, "%.2f", it)
            } ?: base.monthlyUsage,
            modelUsages = consoleData.modelUsages,
            dataSourceLabel = "网页控制台"
        )
    }

    private data class DeepSeekConsoleData(
        val summary: DeepSeekConsoleParser.UserSummary?,
        val monthlyUsage: Double?,
        val modelUsages: List<ModelUsage>
    )

    /**
     * DeepSeek 无 API Key 时的控制台-only 数据源：余额与用量均来自网页控制台
     * （getUserSummary 即含余额钱包 + 按模型用量）。会话缺失/失效则回退 notConfigured。
     */
    private suspend fun fetchDeepSeekConsoleOnly(platform: PlatformType): QuotaInfo {
        val session = runCatching { webSessionStore.getWebSession(platform) }.getOrNull()
        if (session.isNullOrBlank()) return QuotaInfo.notConfigured(platform)
        val cookie = runCatching { webSessionStore.getWebSessionCookie(platform) }.getOrNull()
        val console = runCatching { fetchDeepSeekConsole(session, cookie) }.getOrNull()
        val data = console?.data
            ?: return QuotaInfo.notConfigured(platform)
        val wallet = data.summary?.primaryBalance
        val currency = wallet?.currency?.takeIf { it.isNotBlank() } ?: "CNY"
        return QuotaInfo(
            platform = platform,
            isAvailable = wallet != null,
            isConfigured = true,
            totalBalance = wallet?.let { String.format(Locale.US, "%.2f", it.balance) } ?: "0.00",
            currency = currency,
            monthlyUsage = data.monthlyUsage?.let {
                if (it < 0.01) "0.00" else String.format(Locale.US, "%.2f", it)
            } ?: "0.00",
            modelUsages = data.modelUsages,
            dataSourceLabel = "网页控制台"
        )
    }

    private data class DeepSeekConsoleResult(
        val data: DeepSeekConsoleData?,
        /** 三路请求的 HTTP 状态码（summary/cost/amount 顺序），诊断用。 */
        val codes: List<Int>
    )

    /**
     * 拉取 DeepSeek 控制台数据：用户汇总 + 本月按模型成本 + 本月按模型 Token/请求数（三路并行）。
     * 用量走月度端点（usage/cost|amount?month=&year=）；旧版 by_api_key 端点的
     * start/end/tz 参数已失效（2026-08 真机实测 INVALID_PARAM）。
     * 请求携带登录时的 Cookie（WAF 指纹关联），解析不到任何数据时返回 data=null + 各路 HTTP 码。
     */
    private suspend fun fetchDeepSeekConsole(session: String, cookie: String?): DeepSeekConsoleResult {
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
        // 先读出响应体再解析：body 只能消费一次，顺带打日志供真机诊断
        val summaryBody = summaryResp?.takeIf { it.isSuccessful }?.body()?.string()
        val costBody = costResp?.takeIf { it.isSuccessful }?.body()?.string()
        val amountBody = amountResp?.takeIf { it.isSuccessful }?.body()?.string()
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

        val summary = summaryBody?.let(DeepSeekConsoleParser::parseUserSummary)
        val costRows = costBody?.let { DeepSeekConsoleParser.parseMonthlyTotals(it, costMode = true) }
        val amountRows = amountBody?.let { DeepSeekConsoleParser.parseMonthlyTotals(it, costMode = false) }
        val modelUsages = buildMonthlyModelUsages(costRows, amountRows)
        android.util.Log.i(
            "WatchDogRepo",
            "DeepSeek console parsed: summary=${summary != null} costRows=${costRows?.size} " +
                "amountRows=${amountRows?.size} models=${modelUsages.size}"
        )

        if (summary == null && costRows == null && amountRows == null) {
            return DeepSeekConsoleResult(null, codes)
        }

        // 真实本月用量：优先 summary.monthly_usage；缺失时按月度成本接口的模型金额合计
        val monthlyUsage = summary?.monthlyUsage
            ?: costRows?.sumOf { it.cost }?.takeIf { it > 0.0 }

        return DeepSeekConsoleResult(
            DeepSeekConsoleData(
                summary = summary,
                monthlyUsage = monthlyUsage,
                modelUsages = modelUsages
            ),
            codes
        )
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

    // ===== Kimi =====

    private suspend fun fetchKimiBalance(authHeader: String, platform: PlatformType): QuotaInfo {
        val response = kimiApi.getBalance(authHeader)
        if (!response.isSuccessful) return httpError(platform, response.code())
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

    // ===== Kimi 无 API Key 但有网页会话：用 Cookie 请求控制台 SSR HTML，解析余额/消费 =====

    private suspend fun fetchKimiConsoleSession(platform: PlatformType): QuotaInfo {
        val cookie = runCatching { webSessionStore.getWebSessionCookie(platform) }.getOrNull()
        if (cookie.isNullOrBlank()) {
            // 无 Cookie 但有会话 token：显示"已连接"占位，避免误报未配置
            val hasSession = runCatching { webSessionStore.hasWebSession(platform) }.getOrDefault(false)
            if (!hasSession) return QuotaInfo.notConfigured(platform)
            return QuotaInfo(
                platform = platform, isAvailable = true, isConfigured = true,
                totalBalance = "0.00", currency = "CNY", dataSourceLabel = "网页控制台",
                boosterInfo = "已连接控制台会话，用量明细待接入"
            )
        }
        // 用 Cookie 请求 SSR HTML，解析内嵌金额
        val result = runCatching {
            val resp = kimiConsoleApi.getConsoleHome(cookie)
            if (!resp.isSuccessful) return@runCatching null
            KimiConsoleParser.parse(resp.body()?.string())
        }.getOrNull()
            ?: return QuotaInfo(
                platform = platform, isAvailable = true, isConfigured = true,
                totalBalance = "0.00", currency = "CNY", dataSourceLabel = "网页控制台",
                boosterInfo = "控制台已连接，数据解析中"
            )
        val balance = result.balance ?: "0.00"
        val monthCost = result.monthCost ?: result.totalCost
        return QuotaInfo(
            platform = platform,
            isAvailable = true,
            isConfigured = true,
            totalBalance = balance,
            monthlyUsage = monthCost ?: "0.00",
            currency = "CNY",
            dataSourceLabel = "网页控制台",
            modelUsages = emptyList(),
            boosterInfo = result.totalCost?.let { "累计消费 ¥$it" }
        )
    }

    // ===== GLM =====

    private suspend fun fetchGlmTokenAccounts(authHeader: String, platform: PlatformType): QuotaInfo {
        val response = glmApi.getTokenAccounts(authHeader)
        if (!response.isSuccessful) return httpError(platform, response.code())
        val body = response.body() ?: return QuotaInfo.error(platform, "响应为空")

        val base = buildGlmResourceQuota(body, platform)

        // 叠加 Coding Plan 订阅配额（可选增强）：GLM 用户的 API Key 可能同时有
        // 按量资源包与 Coding Plan 套餐，两者独立展示。Coding Plan 配额接口鉴权
        // 为 "Authorization: <APIKey>"（不加 Bearer），与资源包接口不同。
        val apiKey = runCatching {
            authHeader.removePrefix("Bearer ").trim()
        }.getOrNull()
        if (apiKey.isNullOrBlank()) return base
        val plan = runCatching { glmCodingPlanApi.getQuotaLimit(apiKey) }.getOrNull()
        if (plan != null && plan.isSuccessful) {
            val planBody = plan.body()?.string()
            val result = planBody?.let(GlmCodingPlanParser::parse)
            if (result != null && result.success) {
                val quotaWindows = result.windows.mapNotNull { w ->
                    val usedPercent = w.usedPercent ?: return@mapNotNull null
                    // percentage 为已用百分比，换算 remaining/limit（limit 基准 100）
                    val remaining = (100.0 - usedPercent).coerceIn(0.0, 100.0)
                    QuotaWindow(
                        name = w.name,
                        used = usedPercent,
                        remaining = remaining,
                        limit = 100.0,
                        resetTime = w.nextResetTime,
                        expiresAt = null
                    )
                }
                if (quotaWindows.isNotEmpty() || result.planName != null) {
                    return base.copy(
                        planName = result.planName,
                        quotaWindows = quotaWindows,
                        boosterInfo = null,
                        // 有 Coding Plan 时该卡以订阅模式展示，monthlyUsage/limit 不作为余额语义
                        currency = if (base.currency == "Tokens") "额度" else base.currency
                    )
                }
            }
        }
        return base
    }

    /** 解析 GLM 官方资源包（按量余额）为 QuotaInfo。 */
    private fun buildGlmResourceQuota(body: GlmTokenAccountsResponse, platform: PlatformType): QuotaInfo {
        val allRows = body.rows ?: emptyList()
        // 优先统计有效期内资源包（status=EFFECTIVE）；接口不返回 status 时不过滤
        val effectiveRows = allRows.filter { it.status == null || it.status.equals("EFFECTIVE", true) }
        val rows = if (effectiveRows.isNotEmpty()) effectiveRows else allRows

        var totalRemaining = 0.0
        var totalAmount = 0.0
        val modelUsages = rows.mapNotNull { row ->
            val remain = row.tokenBalance ?: return@mapNotNull null
            totalRemaining += remain
            // 部分响应返回 totalAmount，部分返回 tokensMagnitude，两者兼容
            val amount = row.tokensMagnitude ?: row.totalAmount
            val used = if (amount != null) {
                totalAmount += amount
                (amount - remain).coerceAtLeast(0.0)
            } else {
                0.0
            }
            ModelUsage(
                modelName = row.resourcePackageName?.take(30)
                    ?: row.suitableModel
                    ?: row.tokenNo
                    ?: "未知",
                totalTokens = used.toLong(),
                cost = "${remain.toLong()} / ${amount?.toLong() ?: "?"}"
            )
        }

        if (rows.isEmpty()) {
            return QuotaInfo(
                platform = platform,
                isAvailable = false,
                isConfigured = true,
                totalBalance = "0",
                monthlyUsage = "0",
                monthlyLimit = "0",
                currency = "Tokens",
                modelUsages = emptyList()
            )
        }

        val totalUsed = (totalAmount - totalRemaining).coerceAtLeast(0.0)
        return QuotaInfo(
            platform = platform,
            isAvailable = totalRemaining > 0 || totalAmount > 0,
            isConfigured = true,
            totalBalance = FormatUtils.formatNumber(totalRemaining.toLong()),
            monthlyUsage = FormatUtils.formatNumber(totalUsed.toLong()),
            monthlyLimit = FormatUtils.formatNumber(totalAmount.toLong()),
            currency = "Tokens",
            modelUsages = modelUsages
        )
    }

    // ===== SiliconFlow：官方 /v1/user/info 返回可用余额(balance)、充值余额(chargeBalance)、总余额(totalBalance) =====

    private suspend fun fetchSiliconFlow(authHeader: String, platform: PlatformType): QuotaInfo {
        val resp = siliconFlowApi.getUserInfo(authHeader)
        if (!resp.isSuccessful) return httpError(platform, resp.code())
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

    // ===== 火山方舟：官方无"仅凭方舟 API Key 查余额"的接口（费用中心/管控面需 AK/SK 签名），
    // 不发起任何远程请求，余额完全来自用户手动填写的初始余额 + 本地月度追踪，标记 isEstimate =====

    private suspend fun fetchVolcengineArk(platform: PlatformType): QuotaInfo {
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

    // ===== Kimi Code：订阅配额（GET v1/usages）。
    // 成功响应 JSON 未实测（2026-08 验证仅得 401），接口可能变更；
    // 以下解析全部为防御性兜底，任何字段缺失/类型不符都不抛异常到 UI =====

    private suspend fun fetchKimiCodeUsages(authHeader: String, platform: PlatformType): QuotaInfo {
        val response = kimiCodeApi.getUsages(authHeader)
        if (!response.isSuccessful) return httpError(platform, response.code())

        val body = response.body() ?: return QuotaInfo.error(platform, "响应为空")
        val planName = KimiCodeParser.parsePlanName(body.plan)
        val windows = body.windows?.map { KimiCodeParser.parseWindow(it) } ?: emptyList()
        // 注：顶层 expiresAt/resetTime 无法归属到具体窗口，暂不使用

        if (planName == null && windows.isEmpty()) {
            // HTTP 200 但解析不到任何可识别字段：接口结构很可能已变更
            return QuotaInfo.error(platform, "响应格式无法识别，接口可能已变更")
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

    // ===== 小米 MiMo：官方无"仅凭 API Key"的余额/用量接口（2026-02 实测全部 404/401），
    // 数据来自网页控制台内部接口（platform.xiaomimimo.com 的 /api/v1 路径），
    // 鉴权头 api-platform_ph = 浏览器 Cookie 中的会话值，由用户在设置页粘贴 =====

    private suspend fun fetchMimoConsole(platform: PlatformType): QuotaInfo {
        val session = webSessionStore.getWebSession(platform)
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

        // 2) 余额探测：候选路径逐个尝试（带会话），第一个 200 且可解析出余额者胜。
        //    任何一步失败都不影响令牌套餐数据展示。
        val balance = if (!unauthorized) probeMimoBalance(session) else null

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
            boosterInfo = planDetail?.let { buildMimoPlanDesc(it) },
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

    /** 带会话探测余额类候选路径；首个命中即返回。 */
    private suspend fun probeMimoBalance(session: String): MiMoConsoleParser.BalanceInfo? {
        for (path in MiMoConsoleParser.BALANCE_CANDIDATES) {
            val body = runCatching {
                val resp = mimoConsoleApi.getRaw(session, path)
                if (resp.isSuccessful) resp.body()?.string() else null
            }.getOrNull() ?: continue
            val info = MiMoConsoleParser.parseBalanceInfo(body)
            if (info != null) return info
        }
        return null
    }

    /** 订阅详情 → booster 描述文案（纯字符串，展示在卡片底部）。 */
    private fun buildMimoPlanDesc(d: MiMoConsoleParser.TokenPlanDetail): String? {
        val cycle = d.cycleLabel ?: return null
        val credits = d.totalCredits?.let { FormatUtils.formatNumber(it.toLong()) }
        return if (credits != null) "$cycle · Credits 额度 $credits" else cycle
    }

    private fun httpError(platform: PlatformType, code: Int): QuotaInfo = when (code) {
        401 -> QuotaInfo.error(platform, "API Key 无效（HTTP 401），请检查后重新配置")
        402 -> QuotaInfo.error(platform, "账户余额不足（HTTP 402）")
        else -> QuotaInfo.error(platform, "HTTP $code")
    }
}
