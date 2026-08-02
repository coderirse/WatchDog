package com.example.watchdog.data.repository

import com.example.watchdog.data.api.DeepSeekApi
import com.example.watchdog.data.api.GlmApi
import com.example.watchdog.data.api.KimiApi
import com.example.watchdog.data.api.SiliconFlowApi
import com.example.watchdog.data.local.QuotaCacheStore
import com.example.watchdog.data.local.SettingsStore
import com.example.watchdog.data.model.ModelUsage
import com.example.watchdog.data.model.PlatformType
import com.example.watchdog.data.model.QuotaInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.Locale

class QuotaRepository(
    private val settingsStore: SettingsStore,
    private val cacheStore: QuotaCacheStore,
    private val deepSeekApi: DeepSeekApi,
    private val kimiApi: KimiApi,
    private val glmApi: GlmApi,
    private val siliconFlowApi: SiliconFlowApi
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
            }

            // DeepSeek/Kimi/SiliconFlow 无可用量API，用本地月初余额追踪
            val result = if (platform != PlatformType.GLM
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
        if (!response.isSuccessful) return QuotaInfo.error(platform, "HTTP ${response.code()}")

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
        if (!response.isSuccessful) return QuotaInfo.error(platform, "HTTP ${response.code()}")
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
        if (!response.isSuccessful) return QuotaInfo.error(platform, "HTTP ${response.code()}")
        val body = response.body() ?: return QuotaInfo.error(platform, "响应为空")
        val rows = body.rows ?: emptyList()

        var totalRemaining = 0.0
        var totalAmount = 0.0
        val modelUsages = rows.mapNotNull { row ->
            val remain = row.tokenBalance ?: return@mapNotNull null
            val amount = row.totalAmount ?: return@mapNotNull null
            totalRemaining += remain
            totalAmount += amount
            val used = (amount - remain).coerceAtLeast(0.0)
            ModelUsage(
                modelName = row.resourcePackageName?.take(30) ?: row.tokenNo ?: "未知",
                totalTokens = used.toLong(),
                cost = "${remain.toLong()} / ${amount.toLong()}"
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
                currency = "Tokens"
            )
        }

        val totalUsed = (totalAmount - totalRemaining).coerceAtLeast(0.0)
        return QuotaInfo(
            platform = platform,
            isAvailable = totalRemaining > 0,
            isConfigured = true,
            totalBalance = formatTokenCount(totalRemaining.toLong()),
            monthlyUsage = formatTokenCount(totalUsed.toLong()),
            monthlyLimit = formatTokenCount(totalAmount.toLong()),
            currency = "Tokens",
            modelUsages = modelUsages
        )
    }

    // ===== SiliconFlow：仅余额（billing 接口无官方文档，数据不准） =====

    private suspend fun fetchSiliconFlow(authHeader: String, platform: PlatformType): QuotaInfo {
        val resp = siliconFlowApi.getUserInfo(authHeader)
        if (!resp.isSuccessful) return QuotaInfo.error(platform, "HTTP ${resp.code()}")
        val data = resp.body()?.data
        val totalBalance = data?.totalBalance?.toDoubleOrNull() ?: 0.0
        return QuotaInfo(
            platform = platform,
            isAvailable = resp.body()?.status == true,
            isConfigured = true,
            totalBalance = String.format(Locale.US, "%.2f", totalBalance),
            currency = "CNY"
        )
    }

    private fun formatTokenCount(count: Long): String = when {
        count >= 1_000_000_000 -> String.format(Locale.US, "%.1fB", count / 1_000_000_000.0)
        count >= 1_000_000 -> String.format(Locale.US, "%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format(Locale.US, "%.1fK", count / 1_000.0)
        else -> count.toString()
    }
}
