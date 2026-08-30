package io.github.coderirse.watchdog.data.local

import io.github.coderirse.watchdog.data.model.PlatformType
import io.github.coderirse.watchdog.data.model.QuotaInfo
import io.github.coderirse.watchdog.data.model.QuotaWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BalanceAlertLevelTest {

    private fun quota(
        platform: PlatformType = PlatformType.DEEPSEEK,
        isAvailable: Boolean = true,
        isEstimate: Boolean = false,
        totalBalance: String = "0.00",
        currency: String = "CNY",
        windows: List<QuotaWindow> = emptyList()
    ) = QuotaInfo(
        platform = platform,
        isAvailable = isAvailable,
        isConfigured = true,
        totalBalance = totalBalance,
        currency = currency,
        isEstimate = isEstimate,
        quotaWindows = windows
    )

    @Test
    fun estimatePendingReturnsNull() {
        assertNull(classifyBalanceAlert(quota(isAvailable = false, isEstimate = true), 10.0, 20.0))
    }

    @Test
    fun unavailableReturnsDepleted() {
        assertEquals(BalanceAlertLevel.DEPLETED, classifyBalanceAlert(quota(isAvailable = false), 10.0, 20.0))
    }

    @Test
    fun fractionZeroReturnsDepleted() {
        val q = quota(windows = listOf(QuotaWindow("周", remaining = 0.0, limit = 100.0)))
        assertEquals(BalanceAlertLevel.DEPLETED, classifyBalanceAlert(q, 10.0, 20.0))
    }

    @Test
    fun lowFractionReturnsLow() {
        val q = quota(windows = listOf(QuotaWindow("周", remaining = 10.0, limit = 100.0)))
        assertEquals(BalanceAlertLevel.LOW, classifyBalanceAlert(q, 10.0, 20.0))
    }

    @Test
    fun highFractionReturnsNull() {
        val q = quota(windows = listOf(QuotaWindow("周", remaining = 50.0, limit = 100.0)))
        assertNull(classifyBalanceAlert(q, 10.0, 20.0))
    }

    @Test
    fun customFractionThresholdRaisesBar() {
        // 比例阈值调到 50%：剩余 30% 已低于阈值 → 偏低
        val q = quota(windows = listOf(QuotaWindow("周", remaining = 30.0, limit = 100.0)))
        assertEquals(BalanceAlertLevel.LOW, classifyBalanceAlert(q, 10.0, 50.0))
        // 默认 20% 阈值下 30% 不偏低
        assertNull(classifyBalanceAlert(q, 10.0, 20.0))
    }

    @Test
    fun cnyBelowThresholdReturnsLow() {
        assertEquals(BalanceAlertLevel.LOW, classifyBalanceAlert(quota(totalBalance = "5.00"), 10.0, 20.0))
    }

    @Test
    fun cnyAboveThresholdReturnsNull() {
        assertNull(classifyBalanceAlert(quota(totalBalance = "15.00"), 10.0, 20.0))
    }

    @Test
    fun nonCnyWithoutFractionReturnsNull() {
        // GLM 类 Token 平台：无剩余占比、非 CNY，不套用 CNY 阈值
        val q = quota(platform = PlatformType.GLM, totalBalance = "500", currency = "Tokens")
        assertNull(classifyBalanceAlert(q, 10.0, 20.0))
    }
}
