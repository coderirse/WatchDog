package com.example.watchdog.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SumCnyBalanceTest {

    @Test
    fun sumsConfiguredCnyPlatforms() {
        val quotas = listOf(
            QuotaInfo(PlatformType.DEEPSEEK, true, true, totalBalance = "10.00", currency = "CNY"),
            QuotaInfo(PlatformType.KIMI, true, true, totalBalance = "20.50", currency = "CNY")
        )
        assertEquals(30.50, quotas.sumCnyBalance(), 0.0001)
    }

    @Test
    fun excludesTokenAndSubscriptionPlatforms() {
        val quotas = listOf(
            QuotaInfo(PlatformType.DEEPSEEK, true, true, totalBalance = "10.00", currency = "CNY"),
            QuotaInfo(PlatformType.GLM, true, true, totalBalance = "500", currency = "Tokens"),
            QuotaInfo(PlatformType.KIMI_CODE, true, true, planName = "Pro")
        )
        assertEquals(10.00, quotas.sumCnyBalance(), 0.0001)
    }

    @Test
    fun excludesErrorAndUnconfigured() {
        val quotas = listOf(
            QuotaInfo(PlatformType.DEEPSEEK, true, true, totalBalance = "10.00", currency = "CNY"),
            QuotaInfo(PlatformType.KIMI, true, true, errorMessage = "HTTP 401", currency = "CNY"),
            QuotaInfo.notConfigured(PlatformType.SILICONFLOW)
        )
        assertEquals(10.00, quotas.sumCnyBalance(), 0.0001)
    }

    @Test
    fun freshOnlyExcludesStale() {
        val quotas = listOf(
            QuotaInfo(PlatformType.DEEPSEEK, true, true, totalBalance = "10.00", currency = "CNY"),
            QuotaInfo(PlatformType.KIMI, true, true, totalBalance = "20.00", currency = "CNY", isStale = true)
        )
        // freshOnly=false：包含离线缓存
        assertEquals(30.00, quotas.sumCnyBalance(), 0.0001)
        // freshOnly=true：排除离线缓存
        assertEquals(10.00, quotas.sumCnyBalance(freshOnly = true), 0.0001)
    }
}
