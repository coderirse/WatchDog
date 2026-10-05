package io.github.coderirse.watchdog.ui.components.chart

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 图表语义色集合：柱体 / 选中 / 参考线 / 轴文字。
 * 此前 BarChart 与 LineChart 各自在组合期直读 `MaterialTheme.colorScheme.*`，
 * 收敛到 [rememberChartColors] 一处，两图配色永远同步。
 */
internal data class ChartColors(
    val bar: Color,
    val selected: Color,
    val referenceLine: Color,
    val axisText: Color
)

@Composable
internal fun rememberChartColors(): ChartColors = ChartColors(
    bar = MaterialTheme.colorScheme.primary,
    selected = MaterialTheme.colorScheme.tertiary,
    referenceLine = MaterialTheme.colorScheme.outlineVariant,
    axisText = MaterialTheme.colorScheme.onSurfaceVariant
)
