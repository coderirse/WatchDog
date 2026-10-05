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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp

/**
 * 稀疏数据折线图（余额趋势专用）。
 *
 * 背景：趋势历史刚起步时只有 2–3 个采样点，柱状图会呈现
 * "两根同日柱 + 区间涨跌" 的误读（柱子高低暗示数量对比，实为不同时刻的余额）。
 * 折线 + 圆点 + 每点数值标签更符合"某时刻的余额快照"的心智模型。
 * 数据点 ≥ 4 后切换回 [BarChart]（见 HeroOverviewCard 的分段逻辑）。
 *
 * Y 轴与 [BarChart] 共用 [ChartAxisLayout]：3 档刻度 + TextMeasurer 轴文字。
 */
@Composable
fun LineChart(
    entries: List<ChartEntry>,
    selectedIndex: Int?,
    onSelectIndex: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    valueLabel: (Double) -> String,
    contentDescription: String
) {
    if (entries.isEmpty()) return

    val colors = rememberChartColors()
    val lineColor = colors.bar
    val selectedColor = colors.selected
    val referenceLineColor = colors.referenceLine
    val axisColor = colors.axisText

    val measurer = rememberTextMeasurer()
    val valueStyle = MaterialTheme.typography.labelSmall

    val values = remember(entries) { entries.map { it.value } }
    val domainMin = remember(values) { values.minOrNull() ?: 0.0 }
    val domainMax = remember(values) { values.maxOrNull() ?: 0.0 }
    val ticks = remember(domainMin, domainMax) {
        ChartMath.ticks(domainMin, domainMax, ChartScale.LINEAR)
    }
    val fractions = remember(entries, domainMin, domainMax) {
        values.map { ChartMath.scaleFraction(it, domainMin, domainMax, ChartScale.LINEAR) }
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
            .pointerInput(entries) {
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
            val bottom = layout.top + chartH

            drawYAxis(
                layout = layout,
                tickFractions = ticks.map { it.fraction },
                lineColor = referenceLineColor,
                textColor = axisColor
            )

            if (slot <= 0f || entries.size < 2) return@Canvas

            // 数据点坐标：槽位居中
            val centers = entries.indices.map { i -> layout.left + slot * i + slot / 2f }
            val pointYs = fractions.map { f -> bottom - chartH * f }

            // 折线
            val path = Path().apply {
                moveTo(centers.first(), pointYs.first())
                for (i in 1 until entries.size) lineTo(centers[i], pointYs[i])
            }
            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            // 数据点 + 每点数值标签（点数 ≤3，全部标注；
            // TextMeasurer 内部有布局缓存，绘制期逐次 measure 开销可接受）
            val labelGap = 6.dp.toPx()
            entries.forEachIndexed { i, entry ->
                val isSelected = i == selectedState.value
                drawCircle(
                    color = if (isSelected) selectedColor else lineColor,
                    radius = if (isSelected) 5.dp.toPx() else 4.dp.toPx(),
                    center = Offset(centers[i], pointYs[i])
                )
                val label = measurer.measure(AnnotatedString(valueLabel(entry.value)), valueStyle)
                drawText(
                    label,
                    color = if (isSelected) selectedColor else axisColor,
                    topLeft = Offset(
                        x = (centers[i] - label.size.width / 2f)
                            .coerceIn(layout.left, (size.width - label.size.width - layout.right).coerceAtLeast(layout.left)),
                        y = (pointYs[i] - label.size.height - labelGap).coerceAtLeast(0f)
                    )
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
