package com.example.watchdog.data.repository

import com.example.watchdog.data.api.DeepSeekApi
import com.example.watchdog.data.api.GlmApi
import com.example.watchdog.data.api.KimiApi
import com.example.watchdog.data.api.KimiCodeApi
import com.example.watchdog.data.api.KimiCodeParser
import com.example.watchdog.data.api.SiliconFlowApi
import com.example.watchdog.data.local.QuotaCacheStore
import com.example.watchdog.data.local.SettingsStore
import com.example.watchdog.data.model.ModelUsage
import com.example.watchdog.data.model.PlatformType
import com.example.watchdog.data.model.QuotaInfo
import com.example.watchdog.util.FormatUtils
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.Locale

class QuotaRepository(
    private val settingsStore: SettingsStore,
    private val cacheStore: QuotaCacheStore,
    private val deepSeekApi: DeepSeekApi,
    private val kimiApi: KimiApi,
    private val glmApi: GlmApi,
    private val siliconFlowApi: SiliconFlowApi,
    private val kimiCodeApi: KimiCodeApi
) {
    suspend fun fetchAllQuotas(): List<QuotaInfo> = coroutineScope {
        val configuredPlatforms = settingsStore.getConfiguredPlatforms()
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
            if (apiKey == null) return QuotaInfo.notConfigured(platform)
            val authHeader = "Bearer $apiKey"

            val rawQuota = when (platform) {
                PlatformType.DEEPSEEK -> fetchDeepSeek(authHeader, platform)
                PlatformType.KIMI -> fetchKimiBalance(authHeader, platform)
                PlatformType.GLM -> fetchGlmTokenAccounts(authHeader, platform)
                PlatformType.SILICONFLOW -> fetchSiliconFlow(authHeader, platform)
                PlatformType.VOLCENGINE_ARK -> fetchVolcengineArk(platform)
                PlatformType.KIMI_CODE -> fetchKimiCodeUsages(authHeader, platform)
            }

            // DeepSeek/Kimi 无官方用量API，用本地月初余额快照推算；
            // GLM 有资源包接口、SiliconFlow 有官方可用余额字段，不走本地推算；
            // 火山方舟的本地月度追踪在 fetchVolcengineArk 内部处理（初始余额来自用户输入），
            // Kimi Code 为订阅配额模式，均无远程余额，不参与此推算
            val result = if ((platform == PlatformType.DEEPSEEK || platform == PlatformType.KIMI)
                && rawQuota.isAvailable && rawQuota.errorMessage == null
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

    // ===== DeepSeek：余额（官方未提供用量查询接口，月度用量由本地余额快照推算） =====

    private suspend fun fetchDeepSeek(authHeader: String, platform: PlatformType): QuotaInfo {
        val response = deepSeekApi.getBalance(authHeader)
        if (!response.isSuccessful) return httpError(platform, response.code())

        val body = response.body()
        val balance = body?.balanceInfos?.firstOrNull()

        return QuotaInfo(
            platform = platform,
            isAvailable = body?.isAvailable ?: false,
            isConfigured = true,
            totalBalance = balance?.totalBalance ?: "0.00",
            currency = balance?.currency ?: "CNY"
        )
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

    // ===== GLM =====

    private suspend fun fetchGlmTokenAccounts(authHeader: String, platform: PlatformType): QuotaInfo {
        val response = glmApi.getTokenAccounts(authHeader)
        if (!response.isSuccessful) return httpError(platform, response.code())
        val body = response.body() ?: return QuotaInfo.error(platform, "响应为空")
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

    private fun httpError(platform: PlatformType, code: Int): QuotaInfo = when (code) {
        401 -> QuotaInfo.error(platform, "API Key 无效（HTTP 401），请检查后重新配置")
        402 -> QuotaInfo.error(platform, "账户余额不足（HTTP 402）")
        else -> QuotaInfo.error(platform, "HTTP $code")
    }
}
