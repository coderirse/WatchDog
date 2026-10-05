package io.github.coderirse.watchdog.ui.components.chart

/**
 * 图表纵轴标度。
 *
 * 背景：Token 用量常出现单日尖峰（如 32.7M vs 其余 <1M），
 * 线性标度下其他柱子几乎不可见，需要非线性标度放大相对差异。
 */
enum class ChartScale {
    /** 线性：柱高与数值成正比（默认）。 */
    LINEAR,

    /** 平方根：柱高与 √值成正比，中等程度压缩大值。 */
    SQRT,

    /** 对数(ln(1+v))：强烈压缩大值，对 0 值安全（log1p(0)=0）。 */
    LOG1P
}

/** 一个 Y 轴刻度：[value] 为数值，[fraction] 为其在图表区内的归一化高度（0=底部，1=顶部）。 */
data class ChartTick(val value: Double, val fraction: Float)

/**
 * 图表绘制数学层：纯 Kotlin、无 Android/Compose 依赖，可在 JVM 单测中直接验证。
 *
 * 设计约定：
 * - 所有数值域约定为非负（v ≥ 0）；余额趋势图的"相对区间"映射由调用方先归一化到 [min,max] 再传入；
 * - 非线性标度只影响"数值 → 柱高"的映射，刻度定位必须经 [inverseFraction] 反解，
 *   否则对数/平方根轴的刻度线会画错位置；
 * - 0 值在任何标度下都映射到 0（log1p/sqrt 对 0 均安全）。
 */
object ChartMath {

    /**
     * 根据数据分布推荐标度：
     * - 以**中位数**为基准（均值会被尖峰自身拉高，导致"单尖峰"检测失效）：
     *   最大值 / 中位数 ≥ 500 → 对数（数量级差异极端）；
     *   ≥ 20 → 平方根（单尖峰场景）；
     *   其余 → 线性。
     */
    fun recommendedScale(values: List<Double>): ChartScale {
        val nonZero = values.filter { it > 0.0 }
        if (nonZero.size < 2) return ChartScale.LINEAR
        val max = nonZero.max()
        val sorted = nonZero.sorted()
        val mid = sorted.size / 2
        val median = if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        }
        if (median <= 0.0 || max <= median) return ChartScale.LINEAR
        val ratio = max / median
        return when {
            ratio >= 500.0 -> ChartScale.LOG1P
            ratio >= 20.0 -> ChartScale.SQRT
            else -> ChartScale.LINEAR
        }
    }

    /**
     * 数值 → 归一化高度（0..1）。
     * @param min 数值域下界（用量图为 0，趋势图为区间最小值）
     * @param max 数值域上界，必须 ≥ min
     */
    fun scaleFraction(value: Double, min: Double, max: Double, scale: ChartScale): Float {
        if (max <= min) return 0f
        val t = transform(value.coerceIn(min, max), scale)
        val lo = transform(min, scale)
        val hi = transform(max, scale)
        if (hi <= lo) return 0f
        return ((t - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f)
    }

    /**
     * 归一化高度 → 数值（[scaleFraction] 的反函数）。
     * 供 Y 轴刻度定位：先选定刻度的几何高度，再反解出它代表的数值。
     */
    fun inverseFraction(fraction: Float, min: Double, max: Double, scale: ChartScale): Double {
        if (max <= min) return min
        val f = fraction.coerceIn(0f, 1f)
        val lo = transform(min, scale)
        val hi = transform(max, scale)
        if (hi <= lo) return min
        return inverse(lo + (hi - lo) * f.toDouble(), scale)
    }

    /**
     * 在几何上均匀分布的 Y 轴刻度（按高度均分，数值经反解得出）。
     * 默认 3 档：底部（min 或 0）、中点、顶部（max）。
     */
    fun ticks(min: Double, max: Double, scale: ChartScale, count: Int = 3): List<ChartTick> {
        if (count < 2 || max <= min) {
            return listOf(ChartTick(min, 0f))
        }
        return (0 until count).map { i ->
            val fraction = i.toFloat() / (count - 1)
            ChartTick(inverseFraction(fraction, min, max, scale), fraction)
        }
    }

    /**
     * 时间戳采样点按自然日聚合：同一天保留**最后一条**（余额历史记录的是
     * "变化后"的余额，一天内多次刷新时最后一条最能代表当日终值）。
     *
     * @return 按 ISO 日期（`yyyy-MM-dd`，设备本地时区）升序的 (日期, 数值) 列表
     */
    fun aggregateByDay(points: List<Pair<Long, Double>>): List<Pair<String, Double>> {
        if (points.isEmpty()) return emptyList()
        val format = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val byDay = LinkedHashMap<String, Double>()
        for ((timestamp, value) in points.sortedBy { it.first }) {
            byDay[format.format(java.util.Date(timestamp))] = value
        }
        return byDay.map { (date, value) -> date to value }
    }

    private fun transform(value: Double, scale: ChartScale): Double = when (scale) {
        ChartScale.LINEAR -> value
        ChartScale.SQRT -> kotlin.math.sqrt(value.coerceAtLeast(0.0))
        ChartScale.LOG1P -> kotlin.math.ln1p(value.coerceAtLeast(0.0))
    }

    private fun inverse(transformed: Double, scale: ChartScale): Double = when (scale) {
        ChartScale.LINEAR -> transformed
        ChartScale.SQRT -> {
            val v = transformed * transformed
            if (v.isNaN()) 0.0 else v
        }
        ChartScale.LOG1P -> {
            val v = kotlin.math.exp(transformed) - 1.0
            if (v.isNaN()) 0.0 else v
        }
    }
}
