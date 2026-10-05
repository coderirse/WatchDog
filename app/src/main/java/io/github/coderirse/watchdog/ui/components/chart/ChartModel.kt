package io.github.coderirse.watchdog.ui.components.chart

/**
 * 图表数据条目：共享柱状图/折线图的最小数据契约。
 *
 * @param key       条目的稳定业务标识（如 ISO 日期字符串），由调用方在选中回调中消费
 * @param value     数值（约定 ≥ 0）
 * @param axisLabel 已格式化的 X 轴标签文本
 */
data class ChartEntry(
    val key: String,
    val value: Double,
    val axisLabel: String
)
