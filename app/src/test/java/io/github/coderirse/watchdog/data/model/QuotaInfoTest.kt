package io.github.coderirse.watchdog.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotaInfoTest {

    private fun quota(
        platform: PlatformType = PlatformType.DEEPSEEK,
        planName: String? = null,
        quotaWindows: List<QuotaWindow> = emptyList(),
        modelUsages: List<ModelUsage> = emptyList()
    ) = QuotaInfo(
        platform = platform,
        isAvailable = true,
        isConfigured = true,
        planName = planName,
        quotaWindows = quotaWindows,
        modelUsages = modelUsages
    )

    // ===== isSubscriptionMode =====

    @Test
    fun isSubscriptionMode_trueWhenPlanNameSet() {
        assertTrue(quota(planName = "Pro").isSubscriptionMode)
    }

    @Test
    fun isSubscriptionMode_trueWhenWindowsPresent() {
        assertTrue(quota(quotaWindows = listOf(QuotaWindow("月"))).isSubscriptionMode)
    }

    @Test
    fun isSubscriptionMode_falseByDefault() {
        assertFalse(quota().isSubscriptionMode)
    }

    // ===== lowestRemainingFraction =====

    @Test
    fun lowestRemainingFraction_returnsMinimum() {
        val q = quota(quotaWindows = listOf(
            QuotaWindow("周", remaining = 50.0, limit = 100.0),
            QuotaWindow("月", remaining = 900.0, limit = 1000.0)
        ))
        assertEquals(0.5, q.lowestRemainingFraction!!, 0.0001)
    }

    @Test
    fun lowestRemainingFraction_skipsWindowWithoutLimit() {
        val q = quota(quotaWindows = listOf(
            QuotaWindow("周", remaining = 50.0), // 无 limit
            QuotaWindow("月", remaining = 900.0, limit = 1000.0)
        ))
        assertEquals(0.9, q.lowestRemainingFraction!!, 0.0001)
    }

    @Test
    fun lowestRemainingFraction_nullWhenNoValidWindow() {
        val q = quota(quotaWindows = listOf(QuotaWindow("周", remaining = 50.0)))
        assertNull(q.lowestRemainingFraction)
    }

    @Test
    fun lowestRemainingFraction_nullWhenEmpty() {
        assertNull(quota().lowestRemainingFraction)
    }

    @Test
    fun lowestRemainingFraction_clampsToUnitRange() {
        // remaining 大于 limit 时不应超过 1.0
        val q = quota(quotaWindows = listOf(QuotaWindow("周", remaining = 150.0, limit = 100.0)))
        assertEquals(1.0, q.lowestRemainingFraction!!, 0.0001)
    }

    // ===== 模型用量汇总 =====

    @Test
    fun modelUsageTotals_aggregateCorrectly() {
        val q = quota(modelUsages = listOf(
            ModelUsage("a", requestCount = 3, totalTokens = 100),
            ModelUsage("b", requestCount = 5, totalTokens = 200)
        ))
        assertTrue(q.hasModelUsage)
        assertEquals(8L, q.totalRequestCount)
        assertEquals(300L, q.totalTokensUsed)
    }

    @Test
    fun modelUsageTotals_zeroWhenEmpty() {
        val q = quota()
        assertFalse(q.hasModelUsage)
        assertEquals(0L, q.totalRequestCount)
        assertEquals(0L, q.totalTokensUsed)
    }

    // ===== 工厂方法 =====

    @Test
    fun notConfigured_marksUnconfigured() {
        val q = QuotaInfo.notConfigured(PlatformType.GLM)
        assertFalse(q.isConfigured)
        assertFalse(q.isAvailable)
        assertNull(q.errorMessage)
    }

    @Test
    fun error_marksConfiguredWithMessage() {
        val q = QuotaInfo.error(PlatformType.DEEPSEEK, "HTTP 401")
        assertTrue(q.isConfigured)
        assertFalse(q.isAvailable)
        assertEquals("HTTP 401", q.errorMessage)
    }
}
