package com.example.watchdog.data.repository

import com.example.watchdog.data.api.DeepSeekApi
import com.example.watchdog.data.api.GlmApi
import com.example.watchdog.data.api.KimiApi
import com.example.watchdog.data.api.SiliconFlowApi
import com.example.watchdog.data.local.SettingsStore
import com.example.watchdog.data.model.PlatformType
import com.example.watchdog.data.model.QuotaInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class QuotaRepository(
    private val settingsStore: SettingsStore,
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
            if (apiKey == null) {
                return QuotaInfo.notConfigured(platform)
            }
            val authHeader = "Bearer $apiKey"

            val rawQuota = when (platform) {
                PlatformType.DEEPSEEK -> fetchDeepSeekBalance(authHeader, platform)
                PlatformType.KIMI -> fetchKimiBalance(authHeader, platform)
                PlatformType.GLM -> fetchGlmTokenAccounts(authHeader, platform)
                PlatformType.SILICONFLOW -> fetchSiliconFlowInfo(authHeader, platform)
            }

            // DeepSeek/Kimi 无可用量API，用月初余额追踪计算本月消耗
            // GLM 从 tokenAccounts 接口直接获取，SiliconFlow 从 billing/usage 获取
            if (platform != PlatformType.GLM && platform != PlatformType.SILICONFLOW
                && rawQuota.isAvailable && rawQuota.errorMessage == null) {
                val currentBalance = rawQuota.totalBalance.toDoubleOrNull()
                if (currentBalance != null) {
                    val monthlyUsage = settingsStore.recordBalanceAndGetMonthlyUsage(
                        platform, currentBalance
                    )
                    rawQuota.copy(
                        monthlyUsage = if (monthlyUsage < 0.01) "0.00"
                        else String.format("%.2f", monthlyUsage)
                    )
                } else rawQuota
            } else rawQuota
        } catch (e: Exception) {
            QuotaInfo.error(platform, e.localizedMessage ?: "未知错误")
        }
    }

    // ===== DeepSeek =====

    private suspend fun fetchDeepSeekBalance(authHeader: String, platform: PlatformType): QuotaInfo {
        val response = deepSeekApi.getBalance(authHeader)
        if (response.isSuccessful) {
            val body = response.body()
            val balance = body?.balanceInfos?.firstOrNull()
            return QuotaInfo(
                platform = platform,
                isAvailable = body?.isAvailable ?: false,
                isConfigured = true,
                totalBalance = balance?.totalBalance ?: "0.00",
                currency = balance?.currency ?: "CNY"
            )
        } else {
            return QuotaInfo.error(platform, "HTTP ${response.code()}")
        }
    }

    // ===== Kimi (Moonshot) =====

    private suspend fun fetchKimiBalance(authHeader: String, platform: PlatformType): QuotaInfo {
        val response = kimiApi.getBalance(authHeader)
        if (response.isSuccessful) {
            val body = response.body()
            val data = body?.data
            val totalBalance = data?.availableBalance ?: 0.0
            return QuotaInfo(
                platform = platform,
                isAvailable = totalBalance > 0,
                isConfigured = true,
                totalBalance = String.format("%.2f", totalBalance),
                currency = "CNY"
            )
        } else {
            return QuotaInfo.error(platform, "HTTP ${response.code()}")
        }
    }

    // ===== GLM (智谱) — 使用 tokenAccounts 接口 =====

    private suspend fun fetchGlmTokenAccounts(authHeader: String, platform: PlatformType): QuotaInfo {
        val response = glmApi.getTokenAccounts(authHeader)
        if (response.isSuccessful) {
            val body = response.body()
            if (body == null) {
                return QuotaInfo.error(platform, "响应为空")
            }

            val rows = body.rows ?: emptyList()

            // 汇总所有资源包的 Token 余额
            var totalRemaining = 0.0
            var totalAmount = 0.0
            for (row in rows) {
                totalRemaining += row.tokenBalance ?: 0.0
                totalAmount += row.totalAmount ?: 0.0
            }

            // 计算已使用量 = 总量 - 剩余
            val totalUsed = (totalAmount - totalRemaining).coerceAtLeast(0.0)

            return if (rows.isEmpty()) {
                QuotaInfo(
                    platform = platform,
                    isAvailable = false,
                    isConfigured = true,
                    totalBalance = "0",
                    monthlyUsage = "0",
                    monthlyLimit = "0",
                    currency = "Tokens",
                    errorMessage = "无可用资源包"
                )
            } else {
                QuotaInfo(
                    platform = platform,
                    isAvailable = totalRemaining > 0,
                    isConfigured = true,
                    totalBalance = formatTokenCount(totalRemaining.toLong()),   // 剩余Tokens
                    monthlyUsage = formatTokenCount(totalUsed.toLong()),          // 已使用Tokens
                    monthlyLimit = formatTokenCount(totalAmount.toLong()),        // 总Token量
                    currency = "Tokens"
                )
            }
        } else {
            return QuotaInfo.error(platform, "HTTP ${response.code()}")
        }
    }

    // ===== SiliconFlow =====

    private suspend fun fetchSiliconFlowInfo(authHeader: String, platform: PlatformType): QuotaInfo {
        val response = siliconFlowApi.getUserInfo(authHeader)
        if (response.isSuccessful) {
            val body = response.body()
            val data = body?.data
            val isAvailable = (body?.status == true) &&
                (data?.totalBalance?.toDoubleOrNull()?.let { it > 0 } ?: false)

            // 尝试获取 billing/usage 数据作为本月用量
            var monthlyUsage = "0.00"
            try {
                val billingResp = siliconFlowApi.getBillingUsage(authHeader)
                if (billingResp.isSuccessful) {
                    val billing = billingResp.body()?.data
                    val monthCost = billing?.currentMonthCost
                    if (monthCost != null) {
                        monthlyUsage = monthCost
                    }
                }
            } catch (_: Exception) {
                // billing 端点可能不可用，回退到本地追踪
            }

            return QuotaInfo(
                platform = platform,
                isAvailable = isAvailable,
                isConfigured = true,
                totalBalance = data?.totalBalance ?: "0.00",
                monthlyUsage = monthlyUsage,
                currency = "CNY"
            )
        } else {
            return QuotaInfo.error(platform, "HTTP ${response.code()}")
        }
    }

    private fun formatTokenCount(count: Long): String {
        return when {
            count >= 1_000_000_000 -> String.format("%.1fB", count / 1_000_000_000.0)
            count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
            count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
            else -> count.toString()
        }
    }
}
