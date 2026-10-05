package io.github.coderirse.watchdog.ui.components.chart

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.coderirse.watchdog.util.DateFormats

/**
 * 图表轴布局：由实测文本尺寸推导的四个方向留白 + 预测量的文本布局。
 *
 * 旧实现（HeroOverviewCard / DailyUsageCard 各一份）：
 * - `android.graphics.Paint` 每次 draw 新建、每帧 measureText —— 分配压力与重复代码；
 * - 轴留白硬编码 dp（top=20/bottom=22 等），大字号（fontScale 1.3+）下轴标签被裁切。
 *
 * 新实现：
 * - `TextMeasurer` + `MaterialTheme.typography.labelSmall`：字号是 sp，自动随系统字体缩放；
 * - 留白由实测文本高度/宽度推导，任何 fontScale 下都不裁切；
 * - 布局在组合期预测量并 remember，绘制期零分配。
 */
internal class ChartAxisLayout(
    val left: Float,
    val right: Float,
    val top: Float,
    val bottom: Float,
    /** Y 轴刻度文本布局，与刻度 fraction 列表一一对应 */
    val tickLayouts: List<TextLayoutResult>,
    /** X 轴标签文本布局，与 [xLabels] 一一对应 */
    val xLayouts: List<TextLayoutResult>,
    val xLabels: List<String>
)

/** 标签与绘图区间距（文本右侧到图表左缘）。 */
private val LabelGap = 6.dp

@Composable
internal fun rememberChartAxisLayout(
    xLabels: List<String>,
    tickTexts: List<String>
): ChartAxisLayout {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall

    val tickLayouts = remember(tickTexts, style, measurer) {
        tickTexts.map { measurer.measure(AnnotatedString(it), style) }
    }
    val xLayouts = remember(xLabels, style, measurer) {
        xLabels.map { measurer.measure(AnnotatedString(it), style) }
    }

    val density = LocalDensity.current
    val labelGap = with(density) { LabelGap.toPx() }
    val minTop = with(density) { 8.dp.toPx() }
    val minRight = with(density) { 4.dp.toPx() }
    val labelBottomGap = with(density) { 4.dp.toPx() }

    val maxTickWidth = tickLayouts.maxOfOrNull { it.size.width } ?: 0
    val maxTickHeight = tickLayouts.maxOfOrNull { it.size.height } ?: 0
    val maxLabelHeight = xLayouts.maxOfOrNull { it.size.height } ?: 0

    val left = maxTickWidth + labelGap
    val top = maxOf(minTop, maxTickHeight / 2f + with(density) { 2.dp.toPx() })
    val bottom = maxLabelHeight + labelBottomGap

    return remember(tickLayouts, xLayouts, left, top, minRight, bottom) {
        ChartAxisLayout(
            left = left,
            right = minRight,
            top = top,
            bottom = bottom,
            tickLayouts = tickLayouts,
            xLayouts = xLayouts,
            xLabels = xLabels
        )
    }
}

/** 绘制 Y 轴：每个刻度一条参考线 + 左侧右对齐的刻度文本。 */
internal fun DrawScope.drawYAxis(
    layout: ChartAxisLayout,
    tickFractions: List<Float>,
    lineColor: Color,
    textColor: Color
) {
    val chartW = size.width - layout.left - layout.right
    val chartH = size.height - layout.top - layout.bottom
    val gap = LabelGap.toPx()
    tickFractions.forEachIndexed { index, fraction ->
        val y = layout.top + chartH * (1f - fraction)
        drawLine(
            color = lineColor.copy(alpha = 0.7f),
            start = Offset(layout.left, y),
            end = Offset(layout.left + chartW, y),
            strokeWidth = 1f
        )
        val textLayout = layout.tickLayouts.getOrNull(index) ?: return@forEachIndexed
        drawText(
            textLayout,
            color = textColor,
            topLeft = Offset(
                x = (layout.left - gap - textLayout.size.width).coerceAtLeast(0f),
                y = y - textLayout.size.height / 2f
            )
        )
    }
}

/**
 * 绘制 X 轴标签：按实测宽度抽稀（[DateFormats.thinLabelIndices]），保证相邻标签不叠印。
 */
internal fun DrawScope.drawXAxis(
    layout: ChartAxisLayout,
    slot: Float,
    minGapPx: Float,
    textColor: Color
) {
    if (layout.xLayouts.isEmpty() || slot <= 0f) return
    val keep = DateFormats.thinLabelIndices(
        labels = layout.xLabels,
        slotPx = slot,
        measure = { index -> layout.xLayouts[index].size.width.toFloat() },
        minGapPx = minGapPx
    )
    val baselineGap = with(this) { 2.dp.toPx() }
    keep.forEach { index ->
        val textLayout = layout.xLayouts[index]
        val x = (layout.left + slot * index + (slot - textLayout.size.width) / 2f)
            .coerceIn(layout.left, (size.width - textLayout.size.width - layout.right).coerceAtLeast(layout.left))
        drawText(
            textLayout,
            color = textColor,
            topLeft = Offset(x, size.height - textLayout.size.height - baselineGap)
        )
    }
}
