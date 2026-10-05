package io.github.coderirse.watchdog.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import io.github.coderirse.watchdog.R
import io.github.coderirse.watchdog.data.model.DailyModelUsage
import io.github.coderirse.watchdog.data.model.PlatformType
import io.github.coderirse.watchdog.ui.components.chart.BarChart
import io.github.coderirse.watchdog.ui.components.chart.ChartEntry
import io.github.coderirse.watchdog.ui.components.chart.ChartMath
import io.github.coderirse.watchdog.ui.components.chart.ChartScale
import io.github.coderirse.watchdog.util.DateFormats
import java.util.Calendar
import java.util.Locale

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
    // 纵轴标度：null = 自动（按数据分布推荐），用户选择后固定
    var scaleChoice by remember { mutableStateOf<ChartScale?>(null) }

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
        range.takeIf { it != Range.ALL }?.let { stringResource(rangeLabel(it)) },
        scaleChoice?.let { stringResource(scaleLabel(it)) }
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
                    FilterGroup(labelRes = R.string.daily_usage_scale) {
                        listOf<ChartScale?>(null, ChartScale.LINEAR, ChartScale.SQRT, ChartScale.LOG1P)
                            .forEach { s ->
                                FilterChip(
                                    selected = scaleChoice == s,
                                    onClick = { scaleChoice = s },
                                    label = {
                                        Text(
                                            stringResource(
                                                if (s == null) R.string.daily_usage_scale_auto
                                                else scaleLabel(s)
                                            )
                                        )
                                    }
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
                DailyChartSection(
                    byDate = byDate,
                    metric = metric,
                    scaleChoice = scaleChoice,
                    selectedDate = selectedDate,
                    onSelectDate = { selectedDate = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
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
    val summary = remember(byDate, metric) {
        val lastDate = byDate.keys
            .filter { d -> byDate[d].orEmpty().any { it.totalTokens > 0 } }
            .maxOrNull() ?: byDate.keys.maxOrNull()
        val last = lastDate?.let { byDate[it] }.orEmpty()
        // 任一模型成本未知（cost 端点失败/被拦截）时显示占位符，不把未知渲染成 ¥0.00
        DailySummary(
            lastDate = lastDate,
            value = last.sumOf { metric.totalOf(it) },
            requests = last.sumOf { it.requests },
            costUnknown = last.any { it.cost == null },
            cost = last.sumOf { it.cost ?: 0.0 }
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        SummaryItem(
            stringResource(R.string.daily_usage_latest) +
                (summary.lastDate?.let { " · ${DateFormats.axisLabel(it, withYear = false)}" } ?: ""),
            fmtValue(summary.value, metric)
        )
        SummaryItem(
            stringResource(R.string.daily_usage_requests),
            formatCompact(summary.requests.toLong())
        )
        SummaryItem(
            stringResource(R.string.daily_usage_cost),
            if (summary.costUnknown) "—" else String.format(Locale.US, "¥%.2f", summary.cost)
        )
    }
}

/** [DailySummaryRow] 的缓存数据载体。 */
private data class DailySummary(
    val lastDate: String?,
    val value: Double,
    val requests: Long,
    val costUnknown: Boolean,
    val cost: Double
)

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
 * 图表区：共享 [BarChart] + 自动/手动标度 + 非线性标度提示。
 *
 * 标度（P1）：Token 用量常见单日尖峰（如 32.7M vs 其余 <1M），
 * 线性标度下其他柱子几乎不可见 —— 自动检测到尖峰分布时推荐平方根/对数标度，
 * 并在图下方明示"柱高非线性"，避免误读数值。
 */
@Composable
private fun DailyChartSection(
    byDate: Map<String, List<DailyModelUsage>>,
    metric: Metric,
    scaleChoice: ChartScale?,
    selectedDate: String?,
    onSelectDate: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val dates = remember(byDate) { byDate.keys.toList() }
    val entries = remember(byDate, metric) {
        val withYear = DateFormats.spansMultipleYears(dates)
        dates.map { date ->
            ChartEntry(
                key = date,
                value = byDate[date].orEmpty().sumOf { metric.totalOf(it) },
                axisLabel = DateFormats.axisLabel(date, withYear)
            )
        }
    }
    val values = remember(entries) { entries.map { it.value } }
    val autoScale = remember(entries) { ChartMath.recommendedScale(values) }
    val effectiveScale = scaleChoice ?: autoScale
    val selectedIndex = selectedDate?.let { dates.indexOf(it) }?.takeIf { it >= 0 }

    // 读屏无障碍：Canvas 对 TalkBack 是黑盒，用一段汇总文本替代
    val peak = values.maxOrNull() ?: 0.0
    val summary = stringResource(
        R.string.daily_usage_chart_a11y,
        dates.size,
        fmtValue(values.lastOrNull() ?: 0.0, metric),
        fmtValue(peak, metric)
    )

    BarChart(
        entries = entries,
        selectedIndex = selectedIndex,
        onSelectIndex = { index -> onSelectDate(index?.let { dates[it] }) },
        modifier = modifier,
        scale = effectiveScale,
        valueLabel = { fmtValue(it, metric) },
        contentDescription = summary
    )

    Spacer(modifier = Modifier.height(6.dp))
    if (effectiveScale != ChartScale.LINEAR) {
        Text(
            text = stringResource(
                if (effectiveScale == ChartScale.SQRT) {
                    R.string.daily_usage_scale_hint_sqrt
                } else {
                    R.string.daily_usage_scale_hint_log
                }
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary
        )
        Spacer(modifier = Modifier.height(4.dp))
    }
    Text(
        text = stringResource(R.string.daily_usage_tap_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

@StringRes
private fun scaleLabel(scale: ChartScale): Int = when (scale) {
    ChartScale.LINEAR -> R.string.daily_usage_scale_linear
    ChartScale.SQRT -> R.string.daily_usage_scale_sqrt
    ChartScale.LOG1P -> R.string.daily_usage_scale_log
}

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
 *
 * 默认只显示**最近 7 个有数据的日子**：屏幕截图中长时间跨度下大量 0 值行
 * 会淹没真正有消耗的日子；图表保留零值柱（日期连续性有意义），列表则聚焦有效数据。
 * 按钮双向切换：全部 / 只看有数据的天。
 */
@Composable
private fun DailyAccessibleList(byDate: Map<String, List<DailyModelUsage>>, metric: Metric) {
    var showAll by remember { mutableStateOf(false) }
    val allEntries = byDate.entries.toList()
    val nonZeroEntries = remember(allEntries, metric) {
        allEntries.filter { (_, list) -> list.any { metric.totalOf(it) > 0.0 } }
    }
    val visible = when {
        showAll -> allEntries
        nonZeroEntries.isNotEmpty() -> nonZeroEntries.takeLast(7)
        else -> emptyList()
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    Text(
        text = stringResource(R.string.daily_usage_a11y_list_title),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(4.dp))
    if (visible.isEmpty()) {
        Text(
            text = stringResource(R.string.daily_usage_zero_days_all),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
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
    if (showAll || allEntries.size > visible.size) {
        TextButton(onClick = { showAll = !showAll }) {
            Text(
                stringResource(
                    if (showAll) R.string.daily_usage_a11y_show_nonzero_only
                    else R.string.daily_usage_a11y_show_all,
                    allEntries.size
                )
            )
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
