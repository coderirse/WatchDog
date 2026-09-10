package io.github.coderirse.watchdog.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图表轴标签格式化与抽稀的纯函数测试。
 *
 * 抽稀逻辑用于修复真机截图里的轴标签叠印（`09092630` = `09-26` 与 `09-30` 重叠），
 * 属"看着对但容易写错"的几何判断，故单独覆盖。
 */
class DateFormatsTest {

    // ===== axisLabel =====

    @Test
    fun `轴标签默认省略年份`() {
        assertEquals("09-26", DateFormats.axisLabel("2026-09-26", withYear = false))
    }

    @Test
    fun `跨年时轴标签补年份后两位`() {
        assertEquals("26-09-26", DateFormats.axisLabel("2026-09-26", withYear = true))
    }

    @Test
    fun `非标准日期原样返回且不崩溃`() {
        assertEquals("2026", DateFormats.axisLabel("2026", withYear = true))
        assertEquals("", DateFormats.axisLabel("", withYear = false))
    }

    // ===== spansMultipleYears =====

    @Test
    fun `同年日期不判定为跨年`() {
        assertFalse(DateFormats.spansMultipleYears(listOf("2026-01-01", "2026-12-31")))
    }

    @Test
    fun `跨年日期判定为跨年`() {
        assertTrue(DateFormats.spansMultipleYears(listOf("2025-12-30", "2026-01-02")))
    }

    @Test
    fun `无法解析的日期不触发跨年格式`() {
        assertFalse(DateFormats.spansMultipleYears(listOf("2026-01-01", "bad-date")))
    }

    // ===== thinLabelIndices =====

    /** 固定宽度测量：每个标签都按 [width] 像素计算，便于构造确定的几何场景。 */
    private fun fixedMeasure(width: Float): (Int) -> Float = { width }

    @Test
    fun `槽位足够宽时全部标签保留`() {
        val labels = listOf("01-01", "01-02", "01-03")
        val keep = DateFormats.thinLabelIndices(
            labels = labels,
            slotPx = 100f,
            measure = fixedMeasure(20f),
            minGapPx = 8f
        )
        assertEquals(setOf(0, 1, 2), keep)
    }

    @Test
    fun `槽位过窄时按间距抽稀且末位标签保留`() {
        val labels = (1..10).map { "09-%02d".format(it) }
        // 槽位 30px、标签宽 28px：相邻标签几乎贴在一起，必须抽稀
        val keep = DateFormats.thinLabelIndices(
            labels = labels,
            slotPx = 30f,
            measure = fixedMeasure(28f),
            minGapPx = 8f
        )
        assertTrue("应少于全部标签", keep.size < labels.size)
        assertTrue("末位标签必须保留（最新数据点）", 9 in keep)
        assertTrue("首位标签应保留", 0 in keep)
    }

    @Test
    fun `抽稀结果中相邻标签不叠印`() {
        val labels = (1..30).map { "09-%02d".format(it) }
        val slot = 24f
        val width = 30f
        val keep = DateFormats.thinLabelIndices(
            labels = labels,
            slotPx = slot,
            measure = fixedMeasure(width),
            minGapPx = 8f
        ).sorted()
        // 逐个校验：后一个标签左边界必须在前一个右边界右侧至少 minGap
        keep.zipWithNext().forEach { (a, b) ->
            val aLeft = a * slot + (slot - width) / 2f
            val bLeft = b * slot + (slot - width) / 2f
            assertTrue(
                "标签 $a 与 $b 叠印（间距 ${bLeft - (aLeft + width)}px）",
                bLeft - (aLeft + width) >= 8f
            )
        }
    }

    @Test
    fun `空标签或零槽位返回空集合`() {
        assertTrue(
            DateFormats.thinLabelIndices(emptyList(), 10f, fixedMeasure(5f), 4f).isEmpty()
        )
        assertTrue(
            DateFormats.thinLabelIndices(listOf("a"), 0f, fixedMeasure(5f), 4f).isEmpty()
        )
    }

    @Test
    fun `单个标签时保留该标签`() {
        val keep = DateFormats.thinLabelIndices(listOf("09-01"), 50f, fixedMeasure(20f), 8f)
        assertEquals(setOf(0), keep)
    }

    @Test
    fun `末位与前一位冲突时让位给末位`() {
        val labels = listOf("a", "b")
        // 槽位 20、标签宽 30：两个标签必然重叠，应只保留末位
        val keep = DateFormats.thinLabelIndices(
            labels = labels,
            slotPx = 20f,
            measure = fixedMeasure(30f),
            minGapPx = 8f
        )
        assertEquals(setOf(1), keep)
    }
}
