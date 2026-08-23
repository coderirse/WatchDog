package com.example.watchdog.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.watchdog.R
import com.example.watchdog.data.model.DailyModelUsage
import com.example.watchdog.data.model.PlatformType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Token 用量统计卡：展示按模型 × 按天的 token 消耗（柱状图）。
 * 数据来自 DeepSeek 控制台 usage/cost|amount 接口的 days[].data[].model（按模型 × 按天）。
 *
 * 支持：来源（平台）、模型、指标（总Tokens/输入/输出/请求数/成本）、时间范围（近7天/近30天/全部）。
 * 柱子可点击显示当日明细（该天该来源/模型的输入/输出/请求/成本）。
 */
@Composable
fun DailyUsageCard(usages: List<DailyModelUsage>, modifier: Modifier = Modifier) {
    var selectedPlatform by remember { mutableStateOf<PlatformType?>(null) }
    var selectedModel by remember { mutableStateOf<String?>(null) }
    var metric by remember { mutableStateOf(Metric.TOTAL_TOKENS) }
    var range by remember { mutableStateOf(Range.ALL) }
    var selectedDate by remember { mutableStateOf<String?>(null) }

    val platforms = usages.map { it.platform }.distinct()
    val platFiltered = if (selectedPlatform == null) usages else usages.filter { it.platform == selectedPlatform }
    val models = platFiltered.map { it.model }.distinct().sorted()
    val modelFiltered = if (selectedModel == null) platFiltered else platFiltered.filter { it.model == selectedModel }

    val cutoff = range.startCutoff()
    val rangeFiltered = if (range == Range.ALL || cutoff == null) modelFiltered
        else modelFiltered.filter { it.date >= cutoff }
    val byDate = rangeFiltered.groupBy { it.date }.toSortedMap()

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.daily_usage_title),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(10.dp))

            // 来源筛选（平台）
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PlatformChip(null, platforms, selectedPlatform) { selectedPlatform = it; selectedModel = null }
                platforms.forEach { p ->
                    PlatformChip(p, platforms, selectedPlatform) { selectedPlatform = it; selectedModel = null }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 模型筛选
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ModelChip(null, models, selectedModel) { selectedModel = it }
                models.forEach { m ->
                    ModelChip(m, models, selectedModel) { selectedModel = it }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 指标筛选
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Metric.entries.forEach { m ->
                    MetricChip(m, metric == m) { metric = m }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 时间范围筛选
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Range.entries.forEach { r ->
                    RangeChip(r, range == r) { range = r }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (byDate.isEmpty()) {
                Text(
                    text = stringResource(R.string.daily_usage_empty),
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
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                selectedDate?.let { date ->
                    SelectedDayDetail(date, byDate[date].orEmpty(), platforms)
                }
            }
        }
    }
}

private enum class Range(val label: String, val days: Int?) {
    WEEK("近7天", 7),
    MONTH("近30天", 30),
    ALL("全部", null);

    /** 返回本范围的最早允许日期（ISO yyyy-MM-dd）；全部为 null，不参与过滤。 */
    fun startCutoff(): String? {
        val d = days ?: return null
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_MONTH, -(d - 1))
        val dateFrom = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        return String.format(Locale.US, "%04d-%02d-%02d", dateFrom, month, day)
    }
}

private enum class Metric(val label: String, val totalOf: (DailyModelUsage) -> Double) {
    TOTAL_TOKENS("总Tokens", { it.totalTokens.toDouble() }),
    INPUT("输入", { it.inputTokens.toDouble() }),
    OUTPUT("输出", { it.outputTokens.toDouble() }),
    REQUESTS("请求数", { it.requests.toDouble() }),
    COST("成本", { it.cost })
}

@Composable
private fun PlatformChip(
    platform: PlatformType?,
    all: List<PlatformType>,
    current: PlatformType?,
    onClick: (PlatformType?) -> Unit
) {
    val label = platform?.displayName ?: stringResource(R.string.daily_usage_all)
    val selected = (platform == null && current == null) || (platform != null && current == platform)
    val color = if (platform == null) MaterialTheme.colorScheme.primary else platform.brandColor
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable { onClick(platform) }
            .background(if (selected) color.copy(alpha = 0.2f) else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ModelChip(
    model: String?,
    all: List<String>,
    current: String?,
    onClick: (String?) -> Unit
) {
    val label = model ?: stringResource(R.string.daily_usage_all)
    val selected = (model == null && current == null) || (model != null && current == model)
    val color = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable { onClick(model) }
            .background(if (selected) color.copy(alpha = 0.2f) else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MetricChip(m: Metric, selected: Boolean, onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable { onClick() }
            .background(if (selected) color.copy(alpha = 0.2f) else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = m.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RangeChip(r: Range, selected: Boolean, onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.secondary
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable { onClick() }
            .background(if (selected) color.copy(alpha = 0.2f) else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = r.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
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
    val cost = last.sumOf { it.cost }
    val value = last.sumOf { metric.totalOf(it) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        SummaryItem(stringResource(R.string.daily_usage_latest), fmtValue(value, metric))
        SummaryItem(stringResource(R.string.daily_usage_requests), formatCompact(requests.toLong()))
        SummaryItem(stringResource(R.string.daily_usage_cost), String.format(Locale.US, "¥%.2f", cost))
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

/** Canvas 柱状图：X 轴按日期排布，Y 轴为所选指标；点击某根柱子回调该日期。 */
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
    val dates = byDate.keys.toList()
    val values = dates.map { d -> byDate[d].orEmpty().sumOf { metric.totalOf(it) } }
    val maxV = values.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0

    Box(
        modifier = modifier.pointerInput(dates, values, metric, selectedDate) {
            detectTapGestures { offset ->
                val left = 6.dp.toPx()
                val right = 6.dp.toPx()
                val chartW = size.width - left - right
                val slot = if (dates.isEmpty()) 0f else chartW / dates.size
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
            val top = 8.dp.toPx()
            val bottom = 24.dp.toPx()
            val chartW = size.width - left - right
            val chartH = size.height - top - bottom
            val slot = if (dates.isEmpty()) 0f else chartW / dates.size

            // 柱
            values.forEachIndexed { i, v ->
                val h = (chartH * (v / maxV)).toFloat()
                val xLeft = left + slot * i + slot * 0.2f
                val barW = slot * 0.6f
                val yTop = top + (chartH - h)
                val isSelected = dates[i] == selectedDate
                drawRect(
                    color = (if (isSelected) selectedColor else barColor).copy(alpha = if (v > 0) 0.85f else 0.15f),
                    topLeft = Offset(xLeft, yTop),
                    size = Size(barW, h.coerceAtLeast(1f)),
                    style = Fill
                )
            }

            // 日期轴标签（首/尾 + 每隔几天）
            val labelEvery = (dates.size / 6).coerceAtLeast(1)
            dates.forEachIndexed { i, d ->
                if (i % labelEvery == 0 || i == dates.size - 1) {
                    val short = d.takeLast(5)
                    val x = (left + slot * i + slot * 0.3f).coerceIn(left, size.width - 40.dp.toPx())
                    textLabel(short, x, size.height - 2.dp.toPx())
                }
            }
        }
    }
}

private fun DrawScope.textLabel(date: String?, x: Float, y: Float) {
    if (date == null) return
    drawContext.canvas.nativeCanvas.drawText(
        date,
        x,
        y,
        android.graphics.Paint().apply {
            color = android.graphics.Color.GRAY
            textSize = 11f
            isAntiAlias = true
        }
    )
}

/** 选中某天的明细展示（该天该来源/模型下的各指标）。 */
@Composable
private fun SelectedDayDetail(date: String, dayUsages: List<DailyModelUsage>, _platforms: List<PlatformType>) {
    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.daily_usage_day_detail) + " · $date",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(6.dp))
        // 按模型逐行展示该天明细
        val grouped = dayUsages
            .groupBy { it.model }
            .toSortedMap()
        grouped.forEach { (model, list) ->
            val totalTokens = list.sumOf { it.totalTokens }
            val input = list.sumOf { it.inputTokens }
            val output = list.sumOf { it.outputTokens }
            val requests = list.sumOf { it.requests }
            val cost = list.sumOf { it.cost }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = model,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "总 ${formatCompact(totalTokens)} · 入 ${formatCompact(input)} · 出 ${formatCompact(output)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = formatCompact(requests) + " 次 · ¥" + String.format(Locale.US, "%.2f", cost),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
