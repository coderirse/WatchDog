package io.github.coderirse.watchdog.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 界面日期/时间格式化的唯一入口。
 *
 * 此前 `MM-dd HH:mm` 在各处重复实现（DashboardScreen / PlatformQuotaCard / DailyUsageCard），
 * 其中 `formatTime` 与 `formatDateTime` 实现完全相同却有两个名字；集中到此，
 * 并提供趋势图/柱状图轴标签所需的宽度感知抽稀能力（修复轴标签叠印）。
 *
 * 约定：
 * - 展示给用户的时间统一用设备 Locale 与时区（Locale.getDefault()）；
 * - 数值/金额仍统一用 Locale.US（见各 Provider），两者不要混用。
 */
object DateFormats {

    /** 固定长度短日期 `MM-dd`，用于图表轴标签（宽度可预估）。 */
    private const val PATTERN_SHORT = "MM-dd"

    /** 跨年区间的轴标签，带年份后两位以消除歧义。 */
    private const val PATTERN_SHORT_WITH_YEAR = "yy-MM-dd"

    private const val PATTERN_CLOCK = "HH:mm"
    private const val PATTERN_DATETIME = "MM-dd HH:mm"
    private const val PATTERN_FULL_DATE = "yyyy-MM-dd"

    /** 顶栏/Hero 的钟点时间，如 `18:12`。 */
    fun clock(t: Long): String =
        SimpleDateFormat(PATTERN_CLOCK, Locale.getDefault()).format(Date(t))

    /** 卡片脚注的时间戳，如 `09-10 18:12`。 */
    fun dateTime(t: Long): String =
        SimpleDateFormat(PATTERN_DATETIME, Locale.getDefault()).format(Date(t))

    /** 明细行用的完整日期，如 `2026-09-10`（轴标签会省略年份，明细行不应省略）。 */
    fun fullDate(t: Long): String =
        SimpleDateFormat(PATTERN_FULL_DATE, Locale.US).format(Date(t))

    /**
     * 柱状图/趋势图的 X 轴标签。
     *
     * @param isoDate   数据里的 ISO 日期（`yyyy-MM-dd`），长度不足时原样返回
     * @param withYear  该图的时间跨度是否跨越年份；跨年时补年份避免 `09-10` 产生歧义
     */
    fun axisLabel(isoDate: String, withYear: Boolean): String {
        if (isoDate.length < 10) return isoDate
        val y = isoDate.substring(2, 4)
        val md = isoDate.substring(5, 10)
        return if (withYear) "$y-$md" else md
    }

    /**
     * 按绘制宽度对轴标签做抽稀，保证相邻标签不叠印。
     *
     * 旧实现用「每隔 N 个画一个」的固定步长，不感知文字实际宽度，
     * 30 天数据在窄屏上必然出现两个标签叠印（真机截图中体现为 `09092630`）。
     * 改为按实测宽度逐个判断：只有当前标签左边界距上一个标签右边界有 [minGapPx] 余量才绘制。
     *
     * @param labels      每个槽位的标签文本（已格式化）
     * @param slotPx      每个槽位的宽度（像素）
     * @param measure     按槽位下标返回文本宽度（像素），由绘制侧用 Paint.measureText 预计算
     * @param minGapPx    相邻标签最小间距
     * @param alwaysLast  是否强制绘制最后一个标签（最新数据点通常最需要标注）
     * @return 需要绘制的槽位下标集合（升序）
     */
    fun thinLabelIndices(
        labels: List<String>,
        slotPx: Float,
        measure: (Int) -> Float,
        minGapPx: Float,
        alwaysLast: Boolean = true
    ): Set<Int> {
        if (labels.isEmpty() || slotPx <= 0f) return emptySet()
        val keep = mutableSetOf<Int>()
        var lastRight = Float.NEGATIVE_INFINITY
        labels.forEachIndexed { index, _ ->
            val width = measure(index)
            // 标签在槽位内居中，故其左边界为 index*slot + (slot-width)/2
            val left = index * slotPx + (slotPx - width) / 2f
            if (left - lastRight >= minGapPx) {
                keep += index
                lastRight = left + width
            }
        }
        if (alwaysLast && labels.lastIndex !in keep) {
            // 末位标签若会与前一个叠印，则让位给末位（信息价值更高）
            val lastIndex = labels.lastIndex
            keep.filter { it != lastIndex }.maxOrNull()?.let { keep.remove(it) }
            keep += lastIndex
        }
        return keep
    }

    /**
     * 判断一组 ISO 日期（`yyyy-MM-dd`）是否跨越年份；跨年时轴标签需要带年份。
     * 无法解析出年份的条目直接忽略（例如 "bad-date" 会被忽略），
     * 全部不可解析时返回 false，保持短格式。
     */
    fun spansMultipleYears(isoDates: List<String>): Boolean {
        val years = isoDates.mapNotNull { d ->
            val year = d.take(4).toIntOrNull() ?: return@mapNotNull null
            year.takeIf { d.length == 4 || d.getOrNull(4) == '-' }
        }
        return years.distinct().size > 1
    }
}
