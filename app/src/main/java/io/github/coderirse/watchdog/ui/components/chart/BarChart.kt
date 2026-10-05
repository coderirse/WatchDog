package io.github.coderirse.watchdog.ui.components.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** 条形图柱宽占槽位比例。 */
private const val BAR_WIDTH_FRACTION = 0.6f

/** 柱体在槽位内的左偏移比例（使柱子在槽位居中）。 */
private const val BAR_INSET_FRACTION = 0.2f

/**
 * 共享柱状图（手写 Canvas）：余额趋势与 Token 用量两张图的公共绘制层。
 *
 * 统一解决了旧实现（两份复制粘贴）的共性问题：
 * - 派生数据（values/fractions/ticks）全部 `remember`，滚动重组不再重复计算；
 * - 选择态通过 [androidx.compose.runtime.rememberUpdatedState] 外置，
 *   `pointerInput` 只以数据为 key —— 旧实现把选中值放进 key，每次点选都重建手势协程；
 * - 轴文字走 TextMeasurer（见 [ChartAxisLayout]），随系统 fontScale 缩放、绘制期零分配；
 * - Y 轴 3 档刻度（底/中/顶），刻度位置经 [ChartMath.inverseFraction] 反解，
 *   非线性标度下刻度线仍在正确的几何位置。
 *
 * @param scale          纵轴标度（线性/平方根/对数），影响柱高与刻度数值
 * @param relativeRange  true = 柱高按 [min,max] 相对区间映射（余额趋势）；
 *                       false = 按 [0,max] 映射（用量图，0 值画淡柱）
 * @param minBarFraction 相对区间模式下最矮柱占比（趋势图 0.15f：余额高位运行时保留波动可见性）
 * @param zeroValueAlpha 零值柱透明度（仅非相对区间模式生效）
 * @param barBrush       常规柱的填充画笔（趋势图传纵向渐变，用量图用默认纯色）
 * @param valueLabel     Y 轴刻度数值格式化
 */
@Composable
fun BarChart(
    entries: List<ChartEntry>,
    selectedIndex: Int?,
    onSelectIndex: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    scale: ChartScale = ChartScale.LINEAR,
    relativeRange: Boolean = false,
    minBarFraction: Float = 0f,
    zeroValueAlpha: Float = 0.15f,
    barBrush: (Color) -> Brush = { SolidColor(it) },
    valueLabel: (Double) -> String,
    contentDescription: String
) {
    if (entries.isEmpty()) return

    val colors = rememberChartColors()
    val barColor = colors.bar
    val selectedColor = colors.selected
    val referenceLineColor = colors.referenceLine
    val axisColor = colors.axisText

    val values = remember(entries) { entries.map { it.value } }
    val domainMin = remember(values, relativeRange) {
        if (relativeRange) values.minOrNull() ?: 0.0 else 0.0
    }
    val domainMax = remember(values, relativeRange) {
        val max = values.maxOrNull() ?: 0.0
        // 用量图全零时兜底一个非零上界，避免刻度全部退化为 0
        if (relativeRange) max else max.coerceAtLeast(1.0)
    }
    val ticks = remember(domainMin, domainMax, scale) {
        ChartMath.ticks(domainMin, domainMax, scale)
    }
    val fractions = remember(entries, domainMin, domainMax, scale) {
        values.map { ChartMath.scaleFraction(it, domainMin, domainMax, scale) }
    }
    val tickTexts = ticks.map { valueLabel(it.value) }
    val axisLayout = rememberChartAxisLayout(
        xLabels = entries.map { it.axisLabel },
        tickTexts = tickTexts
    )
    val axisLayoutState = rememberUpdatedState(axisLayout)
    val selectedState = rememberUpdatedState(selectedIndex)
    val entriesState = rememberUpdatedState(entries)

    Box(
        modifier = modifier
            .semantics { this.contentDescription = contentDescription }
            .pointerInput(entries, scale, relativeRange, minBarFraction) {
                detectTapGestures { offset ->
                    val entriesSnapshot = entriesState.value
                    val layout = axisLayoutState.value
                    val chartW = size.width - layout.left - layout.right
                    val slot = if (entriesSnapshot.isEmpty()) 0f else chartW / entriesSnapshot.size
                    if (slot > 0f) {
                        val index = ((offset.x - layout.left) / slot).toInt()
                        if (index in entriesSnapshot.indices) {
                            onSelectIndex(if (index == selectedState.value) null else index)
                        }
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val layout = axisLayoutState.value
            val chartW = (size.width - layout.left - layout.right).coerceAtLeast(0f)
            val chartH = (size.height - layout.top - layout.bottom).coerceAtLeast(0f)
            val slot = if (entries.isEmpty()) 0f else chartW / entries.size
            val corner = 3.dp.toPx()

            drawYAxis(
                layout = layout,
                tickFractions = ticks.map { it.fraction },
                lineColor = referenceLineColor,
                textColor = axisColor
            )

            entries.forEachIndexed { index, entry ->
                val fraction = fractions[index]
                val height = (
                    chartH * (minBarFraction + (1f - minBarFraction) * fraction)
                    ).coerceAtLeast(1f)
                val xLeft = layout.left + slot * index + slot * BAR_INSET_FRACTION
                val barWidth = (slot * BAR_WIDTH_FRACTION).coerceAtLeast(1.5f)
                val yTop = layout.top + chartH - height
                val isSelected = index == selectedState.value
                val color = when {
                    isSelected -> selectedColor
                    entry.value <= 0.0 && !relativeRange -> barColor.copy(alpha = zeroValueAlpha)
                    else -> barColor
                }
                drawRoundRect(
                    brush = barBrush(color),
                    topLeft = Offset(xLeft, yTop),
                    size = Size(barWidth, height),
                    cornerRadius = CornerRadius(corner, corner),
                    style = Fill
                )
            }

            drawXAxis(
                layout = layout,
                slot = slot,
                minGapPx = 8.dp.toPx(),
                textColor = axisColor
            )
        }
    }
}
