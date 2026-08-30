package io.github.coderirse.watchdog.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 月度用量"增量累计"纯函数测试（P1 修复回归保护：月中充值不再清零整月用量）。
 */
class MonthlyUsageComputationTest {

    private fun usage(
        storedMonth: Int,
        currentMonth: Int,
        prevAccum: Double,
        lastBalance: Double?,
        currentBalance: Double
    ) = SettingsStore.computeMonthlyUsage(storedMonth, currentMonth, prevAccum, lastBalance, currentBalance).accumulatedUsage

    @Test
    fun `同月余额下降计入用量`() {
        assertEquals(3.0, usage(month, month, 0.0, 18.0, 15.0), 0.001)
    }

    @Test
    fun `同月多次刷新累计`() {
        // 已有累计 3.0，再降 2.0 → 5.0
        assertEquals(5.0, usage(month, month, 3.0, 15.0, 13.0), 0.001)
    }

    @Test
    fun `月中充值不清零已累计用量`() {
        // 旧方案缺陷场景：月初 10 元，消耗 4 后充值 100 → 余额上升，
        // 累计用量应保持 4.0 而不是被"月初-当前"公式压成 0
        assertEquals(4.0, usage(month, month, 4.0, 6.0, 106.0), 0.001)
    }

    @Test
    fun `跨月从零重新累计`() {
        assertEquals(0.0, usage(month, month + 1, 99.0, 5.0, 4.0), 0.001)
    }

    @Test
    fun `首次记录无基准余额时为零`() {
        assertEquals(0.0, usage(-1, month, 0.0, null, 18.0), 0.001)
    }

    @Test
    fun `累计值不为负`() {
        assertEquals(0.0, usage(month, month, 0.0, 10.0, 12.0), 0.001)
    }

    private val month = 24318 // 2026*12 + 9（任意合法月份标识）
}
