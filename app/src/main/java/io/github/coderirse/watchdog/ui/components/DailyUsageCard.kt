package io.github.coderirse.watchdog.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.StringRes
import io.github.coderirse.watchdog.R
import io.github.coderirse.watchdog.data.model.DailyModelUsage
import io.github.coderirse.watchdog.data.model.PlatformType
import io.github.coderirse.watchdog.util.DateFormats
import java.util.Calendar
import java.util.Locale

/** 图表轴标签字号（sp）。Canvas 内文字不随 Compose typography 缩放，单独定义。 */
private const val AXIS_TEXT_SP = 10f

/**
 * Token 用量统计卡：展示按模型 × 按天的 token 消耗（柱状图）。
 * 数据来自 DeepSeek 控制台 usage/cost|amount 接口的 days[].data[].model（按模型 × 按天）。
 *
 * 本次改进：
 * - 4 行筛选条默认收起（原实现固定占 4 行、把图表挤出首屏），改为一行摘要 + 展开面板；
 * - 筛选控件从自绘 Box 改为 Material3 [FilterChip]（自带 selected 语义与合格触控高度）；
 * - 派生数据（分组/排序）用 remember 缓存，避免 LazyColumn 滚动时每帧重算；
 * - 轴标签抽稀改为按实测文字宽度（修复真机截图里的 `09092630` 叠印），跨年补年份；
 * - 图表补基线/中线参考线与读屏可读的逐日列表。
 */
@Composable
fun DailyUsageCard(usages: List<DailyModelUsage>, modifier: Modifier = Modifier) {
    // 卡片通常只在有数据时渲染；这里仍做兜底，避免调用方遗漏时出现空面板占位
    if (usages.isEmpty()) return

    var filtersExpanded by remember { mutableStateOf(false) }
    var selectedPlatform by remember { mutableStateOf<PlatformType?>(null) }
    var selectedModel by remember { mutableStateOf<String?>(null) }
    var metric by remember { mutableStateOf(Metric.TOTAL_TOKENS) }
    var range by remember { mutableStateOf(Range.ALL) }
    var selectedDate by remember { mutableStateOf<String?>(null) }

    // 来源/模型候选列表只与原始数据有关，缓存避免每帧 distinct + sorted
    val platforms = remember(usages) { usages.map { it.platform }.distinct() }
    val models = remember(usages, selectedPlatform) {
        usages.filter { selectedPlatform == null || it.platform == selectedPlatform }
            .map { it.model }
            .distinct()
            .sorted()
    }
    val byDate = remember(usages, selectedPlatform, selectedModel, range) {
        val cutoff = range.startCutoff()
        usages.asSequence()
            .filter { selectedPlatform == null || it.platform == selectedPlatform }
            .filter { selectedModel == null || it.model == selectedModel }
            .filter { cutoff == null || it.date >= cutoff }
            .groupBy { it.date }
            .toSortedMap()
    }

    val activeFilterCount = listOfNotNull(
        selectedPlatform?.displayName,
        selectedModel,
        metric.takeIf { it != Metric.TOTAL_TOKENS }?.let { stringResource(metricLabel(it)) },
        range.takeIf { it != Range.ALL }?.let { stringResource(rangeLabel(it)) }
    )

    Card(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.daily_usage_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { filtersExpanded = !filtersExpanded }) {
                    Text(
                        text = stringResource(
                            if (filtersExpanded) R.string.daily_usage_filters_hide
                            else R.string.daily_usage_filters_show
                        ),
                        style = MaterialTheme.typography.labelMedium
                    )
                    val rotation by animateFloatAsState(
                        if (filtersExpanded) 180f else 0f,
                        label = "filters-chevron"
                    )
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(18.dp).rotate(rotation)
                    )
                }
            }
            // 收起状态下显示当前筛选摘要，避免"看得见图却不知道筛了什么"
            if (!filtersExpanded) {
                Text(
                    text = if (activeFilterCount.isEmpty()) {
                        stringResource(R.string.daily_usage_filters_default)
                    } else {
                        activeFilterCount.joinToString(" · ")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            AnimatedVisibility(visible = filtersExpanded) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    FilterGroup(labelRes = R.string.daily_usage_source) {
                        AllAndItems(
                            allLabel = stringResource(R.string.daily_usage_all),
                            items = platforms,
                            labelOf = { it.displayName },
                            selected = selectedPlatform,
                            onSelect = { selectedPlatform = it; selectedModel = null }
                        )
                    }
                    FilterGroup(labelRes = R.string.daily_usage_model) {
                        AllAndItems(
                            allLabel = stringResource(R.string.daily_usage_all),
                            items = models,
                            labelOf = { it },
                            selected = selectedModel,
                            onSelect = { selectedModel = it }
                        )
                    }
                    FilterGroup(labelRes = R.string.daily_usage_metric) {
                        Metric.entries.forEach { m ->
                            FilterChip(
                                selected = metric == m,
                                onClick = { metric = m },
                                label = { Text(stringResource(metricLabel(m))) }
                            )
                        }
                    }
                    FilterGroup(labelRes = R.string.daily_usage_range) {
                        Range.entries.forEach { r ->
                            FilterChip(
                                selected = range == r,
                                onClick = { range = r },
                                label = { Text(stringResource(rangeLabel(r))) }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (byDate.isEmpty()) {
                Text(
                    text = stringResource(R.string.daily_usage_empty_filtered),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                DailySummaryRow(byDate, metric)
                Spacer(modifier = Modifier.height(10.dp))
                DailyBarChart(
                    byDate = byDate,
                    metric = metric,
                    selectedDate = selectedDate,
                    onSelectDate = { selectedDate = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.daily_usage_tap_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                selectedDate?.let { date ->
                    SelectedDayDetail(date, byDate[date].orEmpty())
                }
                // 读屏用户无法从 Canvas 读取逐日数值：提供等价的文字列表
                DailyAccessibleList(byDate, metric)
            }
        }
    }
}

/** 一组筛选：小标题 + 可横向滚动的 chip 行。 */
@Composable
private fun FilterGroup(labelRes: Int, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(top = 6.dp)) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            content()
        }
    }
}

/** "全部" + 各项 的通用 chip 组（来源/模型共用）。 */
@Composable
private fun <T> AllAndItems(
    allLabel: String,
    items: List<T>,
    labelOf: (T) -> String,
    selected: T?,
    onSelect: (T?) -> Unit
) {
    FilterChip(
        selected = selected == null,
        onClick = { onSelect(null) },
        label = { Text(allLabel) }
    )
    items.forEach { item ->
        FilterChip(
            selected = selected == item,
            onClick = { onSelect(item) },
            label = { Text(labelOf(item), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        )
    }
}

@StringRes
private fun rangeLabel(range: Range): Int = when (range) {
    Range.WEEK -> R.string.daily_usage_range_7d
    Range.MONTH -> R.string.daily_usage_range_30d
    Range.ALL -> R.string.daily_usage_range_all
}

@StringRes
private fun metricLabel(metric: Metric): Int = when (metric) {
    Metric.TOTAL_TOKENS -> R.string.daily_usage_metric_total
    Metric.INPUT -> R.string.daily_usage_metric_input
    Metric.OUTPUT -> R.string.daily_usage_metric_output
    Metric.REQUESTS -> R.string.daily_usage_metric_requests
    Metric.COST -> R.string.daily_usage_metric_cost
}

private enum class Range(val days: Int?) {
    WEEK(7),
    MONTH(30),
    ALL(null);

    /** 返回本范围的最早允许日期（ISO yyyy-MM-dd）；全部为 null，不参与过滤。 */
    fun startCutoff(): String? {
        val d = days ?: return null
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_MONTH, -(d - 1))
        return String.format(
            Locale.US,
            "%04d-%02d-%02d",
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
    }
}

private enum class Metric(val totalOf: (DailyModelUsage) -> Double) {
    TOTAL_TOKENS({ it.totalTokens.toDouble() }),
    INPUT({ it.inputTokens.toDouble() }),
    OUTPUT({ it.outputTokens.toDouble() }),
    REQUESTS({ it.requests.toDouble() }),
    // cost 为 null 表示该日成本未知（cost 端点失败/被拦截），柱状图按 0 高度处理
    COST({ it.cost ?: 0.0 })
}

/** 选中范围/指标下的最新（含数据）日汇总：当日指标 + 总请求数 + 总成本。 */
@Composable
private fun DailySummaryRow(byDate: Map<String, List<DailyModelUsage>>, metric: Metric) {
    val lastDate = byDate.keys
        .filter { d -> byDate[d].orEmpty().any { it.totalTokens > 0 } }
        .maxOrNull() ?: byDate.keys.maxOrNull()
    val last = lastDate?.let { byDate[it] } ?: emptyList()
    val totalTokens = last.sumOf { it.totalTokens }
    val requests = last.sumOf { it.requests }
    // 任一模型成本未知（cost 端点失败/被拦截）时显示占位符，不把未知渲染成 ¥0.00
    val costUnknown = last.any { it.cost == null }
    val cost = last.sumOf { it.cost ?: 0.0 }
    val value = last.sumOf { metric.totalOf(it) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        SummaryItem(
            stringResource(R.string.daily_usage_latest) +
                (lastDate?.let { " · ${DateFormats.axisLabel(it, withYear = false)}" } ?: ""),
            fmtValue(value, metric)
        )
        SummaryItem(stringResource(R.string.daily_usage_requests), formatCompact(requests.toLong()))
        SummaryItem(
            stringResource(R.string.daily_usage_cost),
            if (costUnknown) "—" else String.format(Locale.US, "¥%.2f", cost)
        )
    }
}

@Composable
private fun SummaryItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Canvas 柱状图：X 轴按日期排布，Y 轴为所选指标；点击某根柱子回调该日期。
 * 抽稀后的轴标签保证互不叠印；颜色与 Hero 趋势图统一（主色 / 选中态用 tertiary）。
 */
@Composable
private fun DailyBarChart(
    byDate: Map<String, List<DailyModelUsage>>,
    metric: Metric,
    selectedDate: String?,
    onSelectDate: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val barColor = MaterialTheme.colorScheme.primary
    val selectedColor = MaterialTheme.colorScheme.tertiary
    val referenceLineColor = MaterialTheme.colorScheme.outlineVariant
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant

    val dates = remember(byDate) { byDate.keys.toList() }
    val values = remember(byDate, metric) { dates.map { d -> byDate[d].orEmpty().sumOf { metric.totalOf(it) } } }
    val maxV = values.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0

    val density = LocalDensity.current
    val axisTextPx = with(density) { AXIS_TEXT_SP.sp.toPx() }
    val labels = remember(dates) {
        val withYear = DateFormats.spansMultipleYears(dates)
        dates.map { DateFormats.axisLabel(it, withYear) }
    }
    val labelWidths = remember(labels, axisTextPx) { labels.map { measureAxisText(it, axisTextPx) } }

    // 读屏无障碍：Canvas 对 TalkBack 是黑盒，用一段汇总文本替代
    val summary = stringResource(
        R.string.daily_usage_chart_a11y,
        dates.size,
        fmtValue(values.lastOrNull() ?: 0.0, metric),
        fmtValue(maxV, metric)
    )

    Box(
        modifier = modifier
            .semantics { contentDescription = summary }
            .pointerInput(dates, selectedDate) {
                detectTapGestures { offset ->
                    val left = 6.dp.toPx()
                    val chartW = size.width - left - 6.dp.toPx()
                    val slot = if (dates.isEmpty()) 0f else chartW / dates.size
                    if (slot <= 0f) return@detectTapGestures
                    val idx = ((offset.x - left) / slot).toInt()
                    if (idx in dates.indices) {
                        onSelectDate(if (dates[idx] == selectedDate) null else dates[idx])
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val left = 6.dp.toPx()
            val right = 6.dp.toPx()
            val top = 20.dp.toPx()
            val bottom = 22.dp.toPx()
            val chartW = size.width - left - right
            val chartH = size.height - top - bottom
            val slot = if (dates.isEmpty()) 0f else chartW / dates.size
            val corner = 3.dp.toPx()

            // 基线 + 半程参考线：给柱高一个读数参照
            listOf(0f, 0.5f, 1f).forEach { fraction ->
                val y = top + chartH * fraction
                drawLine(
                    color = referenceLineColor.copy(alpha = 0.7f),
                    start = Offset(left, y),
                    end = Offset(left + chartW, y),
                    strokeWidth = 1f
                )
            }

            values.forEachIndexed { i, v ->
                val h = (chartH * (v / maxV)).toFloat()
                val xLeft = left + slot * i + slot * 0.2f
                val barW = (slot * 0.6f).coerceAtLeast(1.5f)
                val yTop = top + (chartH - h)
                val isSelected = dates[i] == selectedDate
                drawRoundRect(
                    color = (if (isSelected) selectedColor else barColor)
                        .copy(alpha = if (v > 0) 0.9f else 0.15f),
                    topLeft = Offset(xLeft, yTop),
                    size = Size(barW, h.coerceAtLeast(1f)),
                    cornerRadius = CornerRadius(corner, corner),
                    style = Fill
                )
            }

            // Y 轴最大值提示（左上角）
            drawAxisText(
                text = String.format(Locale.US, "▲ %s", fmtValue(maxV, metric)),
                x = left,
                y = top - 6.dp.toPx(),
                color = axisColor,
                textPx = axisTextPx
            )

            // 日期轴：按实测宽度抽稀（旧实现固定步长导致末位标签叠印成 `09092630`）
            if (labels.isNotEmpty()) {
                val keep = DateFormats.thinLabelIndices(
                    labels = labels,
                    slotPx = slot,
                    measure = { index -> labelWidths[index] },
                    minGapPx = 8.dp.toPx()
                )
                keep.forEach { i ->
                    val width = labelWidths[i]
                    val x = (left + slot * i + (slot - width) / 2f)
                        .coerceIn(left, (size.width - width - right).coerceAtLeast(left))
                    drawAxisText(labels[i], x, size.height - 4.dp.toPx(), axisColor, axisTextPx)
                }
            }
        }
    }
}

/** Canvas 文字绘制（字号已按 density 换算为 px）。 */
private fun DrawScope.drawAxisText(text: String, x: Float, y: Float, color: Color, textPx: Float) {
    drawContext.canvas.nativeCanvas.drawText(text, x, y, buildAxisPaint(color, textPx))
}

private fun buildAxisPaint(color: Color, textPx: Float) = android.graphics.Paint().apply {
    this.color = android.graphics.Color.argb(
        (color.alpha * 255).toInt(),
        (color.red * 255).toInt(),
        (color.green * 255).toInt(),
        (color.blue * 255).toInt()
    )
    textSize = textPx
    isAntiAlias = true
}

private fun measureAxisText(text: String, textPx: Float): Float =
    buildAxisPaint(Color.Black, textPx).measureText(text)

/** 选中某天的明细展示（该天该来源/模型下的各指标）。 */
@Composable
private fun SelectedDayDetail(date: String, dayUsages: List<DailyModelUsage>) {
    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.daily_usage_day_detail) + " · $date",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(6.dp))
        val grouped = dayUsages.groupBy { it.model }.toSortedMap()
        grouped.forEach { (model, list) ->
            val totalTokens = list.sumOf { it.totalTokens }
            val input = list.sumOf { it.inputTokens }
            val output = list.sumOf { it.outputTokens }
            val requests = list.sumOf { it.requests }
            // 任一来源成本未知（cost 端点失败/被拦截）时显示占位符，不把未知渲染成 ¥0.00
            val costUnknown = list.any { it.cost == null }
            val cost = list.sumOf { it.cost ?: 0.0 }
            // 两行布局：模型名 + 数值分行，避免大字号下同行挤压截断
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(
                    text = model,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(
                            R.string.daily_usage_day_detail_tokens,
                            formatCompact(totalTokens),
                            formatCompact(input),
                            formatCompact(output)
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(
                            R.string.daily_usage_day_detail_requests,
                            formatCompact(requests),
                            if (costUnknown) "—" else String.format(Locale.US, "%.2f", cost)
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 逐日文字列表：Canvas 图表对读屏不可读，提供等价的可聚焦文本节点。
 * 默认只展示最近 7 天，避免列表过长；用文本按钮切换全部。
 */
@Composable
private fun DailyAccessibleList(byDate: Map<String, List<DailyModelUsage>>, metric: Metric) {
    var showAll by remember { mutableStateOf(false) }
    val entries = byDate.entries.toList()
    val visible = if (showAll) entries else entries.takeLast(7)

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    Text(
        text = stringResource(R.string.daily_usage_a11y_list_title),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(4.dp))
    visible.forEach { (date, list) ->
        val value = list.sumOf { metric.totalOf(it) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .semantics {
                    contentDescription = "$date, ${fmtValue(value, metric)}"
                },
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = date,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = fmtValue(value, metric),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    if (entries.size > visible.size) {
        TextButton(onClick = { showAll = true }) {
            Text(stringResource(R.string.daily_usage_a11y_show_all, entries.size))
        }
    }
}

private fun fmtValue(v: Double, metric: Metric): String =
    if (metric == Metric.COST) String.format(Locale.US, "¥%.2f", v)
    else formatCompact(v.toLong())

private fun formatCompact(count: Long): String = when {
    count >= 1_000_000_000 -> String.format(Locale.US, "%.1fB", count / 1e9)
    count >= 1_000_000 -> String.format(Locale.US, "%.1fM", count / 1e6)
    count >= 1_000 -> String.format(Locale.US, "%.1fK", count / 1e3)
    else -> count.toString()
}
