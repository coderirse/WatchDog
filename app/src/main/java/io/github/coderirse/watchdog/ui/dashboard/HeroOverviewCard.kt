package io.github.coderirse.watchdog.ui.dashboard

import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.coderirse.watchdog.R
import io.github.coderirse.watchdog.data.model.BalanceSnapshot
import io.github.coderirse.watchdog.data.model.PlatformType
import io.github.coderirse.watchdog.data.model.QuotaInfo
import io.github.coderirse.watchdog.data.model.sumCnyBalance
import io.github.coderirse.watchdog.ui.components.BalanceText
import io.github.coderirse.watchdog.ui.components.chart.BarChart
import io.github.coderirse.watchdog.ui.components.chart.ChartEntry
import io.github.coderirse.watchdog.ui.components.chart.ChartMath
import io.github.coderirse.watchdog.ui.components.chart.LineChart
import io.github.coderirse.watchdog.ui.theme.WatchDogTheme
import io.github.coderirse.watchdog.ui.theme.trendBarBrush
import io.github.coderirse.watchdog.util.DateFormats
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ceil

/** 趋势图最多展示的快照条数（与 BalanceHistoryStore.MAX_ENTRIES 解耦，只取最近的）。 */
private const val TREND_WINDOW = 30

/** 一天毫秒数，用于判断趋势跨度是否在同一天内。 */
private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

/** 少于该点数时用折线图（柱状图在极少数据下会被误读为数量对比）。 */
private const val SPARSE_POINT_LIMIT = 3

/**
 * Hero 总览卡 + 余额趋势图。
 *
 * 视觉定位（本次改版）：Hero 卡改为**主题中性卡**（surface + 描边），
 * 全彩渐变只留给平台品牌卡——此前两者都是渐变、上下相邻 12dp 互相争夺注意力，
 * 用户不知道先读哪一个；中性化后"彩色 = 单个平台、中性 = 全局汇总"成为稳定语义。
 *
 * 同时修复：原 Hero 卡用固定 `onBrandSecondary`（白 78%）叠在深蓝底上，
 * 对比度约 3.2:1 低于 WCAG AA 4.5:1；改用主题色后天然达标。
 *
 * 趋势图并入本卡：余额历史此前"只写不读"（BalanceHistoryStore 记录、
 * DashboardViewModel 加载、AppContainer 注入，但 UI 无任何渲染点），
 * 导致 README 宣传的余额趋势图在 v1.8.0 界面上消失。
 */
@Composable
fun HeroOverviewCard(
    quotas: List<QuotaInfo>,
    balanceHistory: List<BalanceSnapshot>,
    modifier: Modifier = Modifier
) {
    // 派生统计全部 remember(quotas)：此前每次重组（含 LazyColumn 滚动）都重算
    val derived = remember(quotas) {
        val configured = quotas.filter { it.isConfigured }
        HeroStats(
            configuredCount = configured.size,
            abnormalCount = configured.count { it.errorMessage != null },
            staleCount = configured.count { it.isStale },
            totalBalance = quotas.sumCnyBalance(),
            hasSubscription = configured.any { it.isSubscriptionMode },
            estimateCount = configured.count { it.isEstimate },
            oldestDataTime = configured.minOfOrNull { it.lastUpdated }
        )
    }
    with(derived) {
        Card(
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = MaterialTheme.shapes.medium
                    )
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        stringResource(R.string.hero_total_balance),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        BalanceText(
                            text = String.format(Locale.US, "%.2f", totalBalance),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "CNY",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                    if (hasSubscription || estimateCount > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = listOfNotNull(
                                stringResource(R.string.hero_subscription_excluded)
                                    .takeIf { hasSubscription },
                                stringResource(R.string.hero_estimate_included, estimateCount)
                                    .takeIf { estimateCount > 0 }
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(
                                R.string.hero_platform_stats,
                                configuredCount, quotas.size,
                                if (abnormalCount == 0) stringResource(R.string.hero_status_all_normal)
                                else stringResource(R.string.hero_status_abnormal, abnormalCount)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (oldestDataTime != null) {
                            Text(
                                stringResource(R.string.hero_data_time, DateFormats.clock(oldestDataTime)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (staleCount > 0) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.hero_stale_count, staleCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }

                    BalanceTrendSection(balanceHistory)
                }
            }
        }
    }
}

/** Hero 卡的派生统计（[HeroOverviewCard] 内 remember 缓存）。 */
private data class HeroStats(
    val configuredCount: Int,
    val abnormalCount: Int,
    val staleCount: Int,
    val totalBalance: Double,
    val hasSubscription: Boolean,
    val estimateCount: Int,
    val oldestDataTime: Long?
)

/**
 * 余额趋势区（Hero 卡内）。
 *
 * 分段渲染（修复截图问题：短时间内多次刷新产生同日多快照，出现"两根 10-05 的柱子"）：
 * - 快照先按自然日聚合（同日保留最后一条，[ChartMath.aggregateByDay]）；
 * - 0 天 → 整块隐藏；1 天 → 文案提示趋势收集中（单点画柱无意义）；
 * - 2–3 天 → [LineChart]（折线 + 每点数值，避免"柱子高低 = 数量对比"的误读）；
 * - ≥4 天 → [BarChart]（相对区间柱状图，最多 30 点）。
 */
@Composable
private fun BalanceTrendSection(history: List<BalanceSnapshot>) {
    val window = remember(history) { history.takeLast(TREND_WINDOW) }
    val daily = remember(window) {
        ChartMath.aggregateByDay(window.map { it.timestamp to it.balance })
    }
    if (daily.isEmpty()) return

    var expanded by remember { mutableStateOf(true) }
    var selectedIndex by remember(daily) { mutableStateOf<Int?>(null) }

    // 涨跌与跨度：按天聚合后的首末点，跨度 ≤1 天用"今日"文案，
    // 避免"两根同日柱 + 区间 +6.33"暗示一个不存在的长周期
    val delta = daily.last().second - daily.first().second
    val spanDays = remember(window) {
        val first = window.minOf { it.timestamp }
        val last = window.maxOf { it.timestamp }
        ceil((last - first) / MILLIS_PER_DAY.toDouble()).toLong().coerceAtLeast(1)
    }
    val sign = if (delta >= 0) "+" else "-"
    val amount = String.format(Locale.US, "%.2f", abs(delta))
    val deltaText = if (spanDays <= 1) {
        stringResource(R.string.hero_trend_delta_today, sign, amount)
    } else {
        stringResource(R.string.hero_trend_delta_days, spanDays, sign, amount)
    }

    Spacer(modifier = Modifier.height(14.dp))
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.hero_trend_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = deltaText,
            style = MaterialTheme.typography.labelMedium,
            color = if (delta >= 0) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.error
            }
        )
        if (daily.size >= 2) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    text = stringResource(
                        if (expanded) R.string.hero_trend_collapse else R.string.hero_trend_expand
                    ),
                    style = MaterialTheme.typography.labelSmall
                )
                val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "trend-toggle")
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp).rotate(rotation)
                )
            }
        }
    }

    // 单点：没有可绘制的趋势，只提示正在积累
    if (daily.size == 1) {
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(
                R.string.hero_trend_collecting,
                daily.first().first,
                String.format(Locale.US, "%.2f", daily.first().second)
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.hero_trend_hint_sparse),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
        return
    }

    if (!expanded) return

    val entries = remember(daily) {
        val withYear = DateFormats.spansMultipleYears(daily.map { it.first })
        daily.map { (date, balance) ->
            ChartEntry(key = date, value = balance, axisLabel = DateFormats.axisLabel(date, withYear))
        }
    }
    val isSparse = entries.size <= SPARSE_POINT_LIMIT
    val summary = stringResource(
        if (isSparse) R.string.hero_trend_a11y_line else R.string.hero_trend_a11y,
        entries.size,
        String.format(Locale.US, "%.2f", daily.minOf { it.second }),
        String.format(Locale.US, "%.2f", daily.maxOf { it.second })
    )

    if (isSparse) {
        LineChart(
            entries = entries,
            selectedIndex = selectedIndex,
            onSelectIndex = { selectedIndex = it },
            valueLabel = { String.format(Locale.US, "%.2f", it) },
            contentDescription = summary,
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
        )
    } else {
        BarChart(
            entries = entries,
            selectedIndex = selectedIndex,
            onSelectIndex = { selectedIndex = it },
            relativeRange = true,
            minBarFraction = 0.15f,
            barBrush = { trendBarBrush(it) },
            valueLabel = { String.format(Locale.US, "%.2f", it) },
            contentDescription = summary,
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
        )
    }

    val detail = selectedIndex?.let { daily.getOrNull(it) } ?: daily.last()
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = if (selectedIndex != null) {
            stringResource(
                R.string.hero_trend_point_selected,
                detail.first,
                String.format(Locale.US, "%.2f", detail.second)
            )
        } else {
            stringResource(
                R.string.hero_trend_latest,
                String.format(Locale.US, "%.2f", detail.second)
            )
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(2.dp))
    Text(
        text = stringResource(
            if (isSparse) R.string.hero_trend_hint_sparse else R.string.hero_trend_hint
        ),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline
    )
}

// ===== Preview =====

private val previewSnapshots = (0 until 24).map { i ->
    BalanceSnapshot(
        timestamp = System.currentTimeMillis() - TimeUnit.DAYS.toMillis((23 - i).toLong()),
        balance = 40.0 + (i % 7) * 2.5
    )
}

/** 单点：趋势刚起步（展示"收集中"文案）。 */
private val previewSingleSnapshot = listOf(
    BalanceSnapshot(timestamp = System.currentTimeMillis(), balance = 6.33)
)

/** 两点同日：此前会画出两根同日柱，现在聚合为 1 天。 */
private val previewSameDaySnapshots = listOf(
    BalanceSnapshot(timestamp = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(3), balance = 3.0),
    BalanceSnapshot(timestamp = System.currentTimeMillis(), balance = 6.33)
)

/** 3 天稀疏趋势（折线图分支）。 */
private val previewSparseSnapshots = (0 until 3).map { i ->
    BalanceSnapshot(
        timestamp = System.currentTimeMillis() - TimeUnit.DAYS.toMillis((2 - i).toLong()),
        balance = 2.0 + i * 2.16
    )
}

/** 5 天趋势（柱状图分支的最少数据）。 */
private val previewFiveDaySnapshots = (0 until 5).map { i ->
    BalanceSnapshot(
        timestamp = System.currentTimeMillis() - TimeUnit.DAYS.toMillis((4 - i).toLong()),
        balance = 3.0 + (i % 3) * 1.5
    )
}

private val previewHeroQuotas = listOf(
    QuotaInfo(
        platform = PlatformType.DEEPSEEK,
        isAvailable = true,
        isConfigured = true,
        totalBalance = "36.50"
    ),
    QuotaInfo(
        platform = PlatformType.KIMI_CODE,
        isAvailable = true,
        isConfigured = true,
        planName = "Andante 套餐"
    ),
    QuotaInfo.error(PlatformType.GLM, "HTTP 401")
)

@Preview(name = "Hero总览卡-浅色", showBackground = true)
@Composable
private fun HeroOverviewCardPreviewLight() {
    WatchDogTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            HeroOverviewCard(quotas = previewHeroQuotas, balanceHistory = previewSnapshots)
        }
    }
}

@Preview(name = "Hero总览卡-深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HeroOverviewCardPreviewDark() {
    WatchDogTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            HeroOverviewCard(quotas = previewHeroQuotas, balanceHistory = previewSnapshots)
        }
    }
}

@Preview(name = "趋势-单点收集中", showBackground = true)
@Composable
private fun HeroTrendSinglePointPreview() {
    WatchDogTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            HeroOverviewCard(quotas = previewHeroQuotas, balanceHistory = previewSingleSnapshot)
        }
    }
}

@Preview(name = "趋势-同日两点聚合", showBackground = true)
@Composable
private fun HeroTrendSameDayPreview() {
    WatchDogTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            HeroOverviewCard(quotas = previewHeroQuotas, balanceHistory = previewSameDaySnapshots)
        }
    }
}

@Preview(name = "趋势-3天折线", showBackground = true)
@Composable
private fun HeroTrendSparseLinePreview() {
    WatchDogTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            HeroOverviewCard(quotas = previewHeroQuotas, balanceHistory = previewSparseSnapshots)
        }
    }
}

@Preview(name = "趋势-5天柱状", showBackground = true)
@Composable
private fun HeroTrendFiveDayBarPreview() {
    WatchDogTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            HeroOverviewCard(quotas = previewHeroQuotas, balanceHistory = previewFiveDaySnapshots)
        }
    }
}
