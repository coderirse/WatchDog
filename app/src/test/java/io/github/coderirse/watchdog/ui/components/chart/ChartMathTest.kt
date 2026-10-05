package io.github.coderirse.watchdog.ui.components.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * [ChartMath] 的 JVM 单测：标度映射单调性、0 值安全、刻度反解一致性、按天聚合。
 */
class ChartMathTest {

    // ===== recommendedScale =====

    @Test
    fun `recommendedScale returns LINEAR for flat data`() {
        val values = listOf(10.0, 12.0, 11.0, 9.0)
        assertEquals(ChartScale.LINEAR, ChartMath.recommendedScale(values))
    }

    @Test
    fun `recommendedScale returns SQRT for single spike`() {
        // 32.7M 尖峰 vs 均值 ~1M 的真实场景
        val values = listOf(0.0, 1_000_000.0, 32_700_000.0, 500_000.0, 300_000.0)
        assertEquals(ChartScale.SQRT, ChartMath.recommendedScale(values))
    }

    @Test
    fun `recommendedScale returns LOG1P for extreme magnitude spread`() {
        val values = listOf(1.0, 10.0, 100_000.0)
        assertEquals(ChartScale.LOG1P, ChartMath.recommendedScale(values))
    }

    @Test
    fun `recommendedScale handles fewer than two nonzero values`() {
        assertEquals(ChartScale.LINEAR, ChartMath.recommendedScale(listOf(0.0, 0.0)))
        assertEquals(ChartScale.LINEAR, ChartMath.recommendedScale(listOf(5.0)))
        assertEquals(ChartScale.LINEAR, ChartMath.recommendedScale(emptyList()))
    }

    // ===== scaleFraction =====

    @Test
    fun `scaleFraction is monotonic non-decreasing for every scale`() {
        ChartScale.entries.forEach { scale ->
            var prev = -1f
            for (i in 0..100) {
                val v = i / 100.0 * 100.0
                val f = ChartMath.scaleFraction(v, 0.0, 100.0, scale)
                assertTrue("scale=$scale v=$v", f >= prev)
                assertTrue("scale=$scale v=$v", f in 0f..1f)
                prev = f
            }
        }
    }

    @Test
    fun `scaleFraction endpoints map to 0 and 1`() {
        ChartScale.entries.forEach { scale ->
            assertEquals(0f, ChartMath.scaleFraction(0.0, 0.0, 100.0, scale), 1e-6f)
            assertEquals(1f, ChartMath.scaleFraction(100.0, 0.0, 100.0, scale), 1e-6f)
        }
    }

    @Test
    fun `scaleFraction is zero-safe even on LOG1P`() {
        assertEquals(0f, ChartMath.scaleFraction(0.0, 0.0, 32_700_000.0, ChartScale.LOG1P), 1e-6f)
    }

    @Test
    fun `scaleFraction SQRT boosts small values compared to LINEAR`() {
        // 尖峰场景下，小值在 sqrt 标度下应获得更高的柱子
        val linear = ChartMath.scaleFraction(1_000_000.0, 0.0, 32_700_000.0, ChartScale.LINEAR)
        val sqrt = ChartMath.scaleFraction(1_000_000.0, 0.0, 32_700_000.0, ChartScale.SQRT)
        assertTrue("linear=$linear sqrt=$sqrt", sqrt > linear)
    }

    @Test
    fun `scaleFraction handles degenerate domain`() {
        assertEquals(0f, ChartMath.scaleFraction(5.0, 5.0, 5.0, ChartScale.LINEAR))
        assertEquals(0f, ChartMath.scaleFraction(5.0, 6.0, 5.0, ChartScale.SQRT))
    }

    @Test
    fun `scaleFraction works with positive min domain`() {
        // 趋势图的相对区间映射：min=6, max=10
        assertEquals(0f, ChartMath.scaleFraction(6.0, 6.0, 10.0, ChartScale.SQRT), 1e-6f)
        assertEquals(1f, ChartMath.scaleFraction(10.0, 6.0, 10.0, ChartScale.SQRT), 1e-6f)
    }

    // ===== inverseFraction / ticks =====

    @Test
    fun `inverseFraction is the inverse of scaleFraction`() {
        ChartScale.entries.forEach { scale ->
            for (i in 0..10) {
                val f = i / 10f
                val v = ChartMath.inverseFraction(f, 0.0, 100.0, scale)
                val back = ChartMath.scaleFraction(v, 0.0, 100.0, scale)
                assertEquals("scale=$scale f=$f", f, back, 1e-4f)
            }
        }
    }

    @Test
    fun `ticks are geometrically even and respect count`() {
        val ticks = ChartMath.ticks(0.0, 100.0, ChartScale.SQRT, count = 3)
        assertEquals(3, ticks.size)
        assertEquals(0f, ticks[0].fraction, 1e-6f)
        assertEquals(0.5f, ticks[1].fraction, 1e-6f)
        assertEquals(1f, ticks[2].fraction, 1e-6f)
        // 反解出的数值必须能映射回对应高度
        ticks.forEach { tick ->
            assertEquals(
                tick.fraction,
                ChartMath.scaleFraction(tick.value, 0.0, 100.0, ChartScale.SQRT),
                1e-4f
            )
        }
    }

    @Test
    fun `ticks fall back to single tick on degenerate domain`() {
        val ticks = ChartMath.ticks(7.0, 7.0, ChartScale.LINEAR, count = 3)
        assertEquals(1, ticks.size)
        assertEquals(7.0, ticks[0].value, 1e-9)
        assertEquals(0f, ticks[0].fraction, 1e-6f)
    }

    // ===== aggregateByDay =====

    @Test
    fun `aggregateByDay keeps the last sample per day and sorts by date`() {
        val tz = TimeZone.getTimeZone("Asia/Shanghai")
        val cal = Calendar.getInstance(tz)
        fun at(y: Int, mo: Int, d: Int, h: Int): Long {
            cal.set(y, mo - 1, d, h, 0, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
        Locale.setDefault(Locale.US)
        val points = listOf(
            at(2026, 10, 5, 9) to 1.0,   // 同日较早
            at(2026, 10, 4, 20) to 0.5,  // 更早的日期但列表顺序在后
            at(2026, 10, 5, 21) to 6.33  // 同日较晚 → 保留
        )
        val aggregated = ChartMath.aggregateByDay(points)
        assertEquals(listOf("2026-10-04" to 0.5, "2026-10-05" to 6.33), aggregated)
    }

    @Test
    fun `aggregateByDay handles empty input`() {
        assertTrue(ChartMath.aggregateByDay(emptyList()).isEmpty())
    }

    @Test
    fun `aggregateByDay collapses two same-day bars from trend history`() {
        // 复现截图问题：短时间内多次刷新产生同日多点 → 两根 10-05 的柱子
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        val cal = Calendar.getInstance()
        cal.set(2026, 9, 5, 8, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val morning = cal.timeInMillis
        cal.set(2026, 9, 5, 20, 0, 0)
        val evening = cal.timeInMillis
        val aggregated = ChartMath.aggregateByDay(listOf(morning to 3.0, evening to 6.33))
        assertEquals(listOf("2026-10-05" to 6.33), aggregated)
    }
}
