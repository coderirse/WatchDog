package io.github.coderirse.watchdog.ui.dashboard

import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.coderirse.watchdog.R
import io.github.coderirse.watchdog.data.model.BalanceSnapshot
import io.github.coderirse.watchdog.data.model.PlatformType
import io.github.coderirse.watchdog.data.model.QuotaInfo
import io.github.coderirse.watchdog.data.model.sumCnyBalance
import io.github.coderirse.watchdog.ui.theme.WatchDogTheme
import io.github.coderirse.watchdog.ui.theme.balanceNumeral
import io.github.coderirse.watchdog.ui.theme.trendBarBrush
import io.github.coderirse.watchdog.util.DateFormats
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** 趋势图最多展示的快照条数（与 BalanceHistoryStore.MAX_ENTRIES 解耦，只取最近的）。 */
private const val TREND_WINDOW = 30

/** 图表轴标签字号（sp），Canvas 内文字不随 Compose typography 缩放，单独定义。 */
private const val AXIS_TEXT_SP = 10f

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
    val configured = quotas.filter { it.isConfigured }
    val abnormalCount = configured.count { it.errorMessage != null }
    val staleCount = configured.count { it.isStale }
    // 金额汇总：仅计按量付费且以 CNY 计价的平台；
    // 订阅配额平台无余额概念，GLM 等以 Token 计价的平台不能混入金额求和
    val totalBalance = quotas.sumCnyBalance()
    val hasSubscription = configured.any { it.isSubscriptionMode }
    val estimateCount = configured.count { it.isEstimate }
    // 取最早的数据时间而非最新：给出"数据新鲜度下界"，
    // 避免某平台仍在用两天前的缓存、Hero 却显示"刚刚更新"的错觉
    val oldestDataTime = configured.minOfOrNull { it.lastUpdated }

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
                    Text(
                        text = String.format(Locale.US, "%.2f", totalBalance),
                        style = balanceNumeral,
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
                            configured.size, quotas.size,
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

/**
 * 余额趋势区（Hero 卡内）。历史不足 2 点时整块隐藏，避免出现无意义的单柱图。
 * 头部一行展示区间涨跌，右侧按钮折叠/展开图表，默认展开。
 */
@Composable
private fun BalanceTrendSection(history: List<BalanceSnapshot>) {
    if (history.size < 2) return

    var expanded by remember { mutableStateOf(true) }
    val window = remember(history) { history.takeLast(TREND_WINDOW) }
    var selectedIndex by remember(window) { mutableStateOf<Int?>(null) }

    val delta = window.last().balance - window.first().balance

    Spacer(modifier = Modifier.height(14.dp))
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.hero_trend_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = stringResource(
                R.string.hero_trend_delta,
                if (delta >= 0) "+" else "-",
                String.format(Locale.US, "%.2f", abs(delta))
            ),
            style = MaterialTheme.typography.labelMedium,
            color = if (delta >= 0) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.error
            }
        )
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

    if (!expanded) return

    BalanceTrendChart(
        snapshots = window,
        selectedIndex = selectedIndex,
        onSelect = { selectedIndex = it },
        modifier = Modifier
            .fillMaxWidth()
            .height(132.dp)
    )

    val detail = selectedIndex?.let { window.getOrNull(it) } ?: window.last()
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = if (selectedIndex != null) {
            stringResource(
                R.string.hero_trend_point_selected,
                DateFormats.fullDate(detail.timestamp),
                String.format(Locale.US, "%.2f", detail.balance)
            )
        } else {
            stringResource(
                R.string.hero_trend_latest,
                String.format(Locale.US, "%.2f", detail.balance)
            )
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(2.dp))
    Text(
        text = stringResource(R.string.hero_trend_hint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline
    )
}

/** Canvas 柱状趋势图：X 轴按快照顺序，Y 轴为余额；点击柱子查看该时间点余额。 */
@Composable
private fun BalanceTrendChart(
    snapshots: List<BalanceSnapshot>,
    selectedIndex: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    val barColor = MaterialTheme.colorScheme.primary
    val selectedColor = MaterialTheme.colorScheme.tertiary
    val referenceLineColor = MaterialTheme.colorScheme.outlineVariant
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant

    val values = snapshots.map { it.balance }
    val maxV = values.maxOrNull() ?: 0.0
    val minV = values.minOrNull() ?: 0.0
    // 柱高按"相对区间"而非"相对 0"：否则余额在高位运行时所有柱子几乎等高，看不出波动
    val range = (maxV - minV).coerceAtLeast(0.01)

    // 轴标签与测量：文字宽度用于抽稀，必须在 Composable 侧用 px 字号测量
    val density = androidx.compose.ui.platform.LocalDensity.current
    val axisTextPx = with(density) { AXIS_TEXT_SP.sp.toPx() }
    val labels = remember(snapshots) {
        val iso = snapshots.map { isoDateOf(it.timestamp) }
        val withYear = DateFormats.spansMultipleYears(iso)
        iso.map { DateFormats.axisLabel(it, withYear) }
    }
    val labelWidths = remember(labels, axisTextPx) { labels.map { measureText(it, axisTextPx) } }

    val summary = stringResource(
        R.string.hero_trend_a11y,
        snapshots.size,
        String.format(Locale.US, "%.2f", minV),
        String.format(Locale.US, "%.2f", maxV)
    )

    Box(
        modifier = modifier
            .semantics { contentDescription = summary }
            .pointerInput(snapshots, selectedIndex) {
                detectTapGestures { offset ->
                    val left = 4.dp.toPx()
                    val chartW = size.width - left * 2
                    val slot = if (snapshots.isEmpty()) 0f else chartW / snapshots.size
                    if (slot <= 0f) return@detectTapGestures
                    val idx = ((offset.x - left) / slot).toInt()
                    if (idx in snapshots.indices) {
                        onSelect(if (idx == selectedIndex) null else idx)
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val left = 4.dp.toPx()
            val right = 4.dp.toPx()
            val top = 8.dp.toPx()
            val bottom = 18.dp.toPx()
            val chartW = size.width - left - right
            val chartH = size.height - top - bottom
            val slot = if (snapshots.isEmpty()) 0f else chartW / snapshots.size
            val corner = 3.dp.toPx()

            // 基线 + 中线：给柱高一个读数参照（原图完全没有参考线）
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
                val norm = ((v - minV) / range).toFloat().coerceIn(0f, 1f)
                val h = chartH * (0.15f + 0.85f * norm)
                val xLeft = left + slot * i + slot * 0.22f
                val barW = (slot * 0.56f).coerceAtLeast(1.5f)
                val yTop = top + (chartH - h)
                if (i == selectedIndex) {
                    drawRoundRect(
                        color = selectedColor,
                        topLeft = Offset(xLeft, yTop),
                        size = Size(barW, h),
                        cornerRadius = CornerRadius(corner, corner),
                        style = Fill
                    )
                } else {
                    drawRoundRect(
                        brush = trendBarBrush(barColor),
                        topLeft = Offset(xLeft, yTop),
                        size = Size(barW, h),
                        cornerRadius = CornerRadius(corner, corner),
                        style = Fill
                    )
                }
            }

            // 上限值标注（左上角）
            drawAxisText(
                text = String.format(Locale.US, "%.2f", maxV),
                x = left,
                y = top + 9.dp.toPx(),
                color = axisColor,
                textPx = axisTextPx
            )

            // 日期轴：按实测文字宽度抽稀（修复旧实现固定步长导致的标签叠印）
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
                        .coerceIn(left, (size.width - width - left).coerceAtLeast(left))
                    drawAxisText(
                        text = labels[i],
                        x = x,
                        y = size.height - 5.dp.toPx(),
                        color = axisColor,
                        textPx = axisTextPx
                    )
                }
            }
        }
    }
}

/** Canvas 文字绘制（字号已按 density 换算为 px）。 */
private fun DrawScope.drawAxisText(
    text: String,
    x: Float,
    y: Float,
    color: Color,
    textPx: Float
) {
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

private fun measureText(text: String, textPx: Float): Float =
    buildAxisPaint(Color.Black, textPx).measureText(text)

/** 时间戳 → 本地日期 `yyyy-MM-dd`（与每日用量数据的日期字符串同构，便于统一抽稀）。 */
private fun isoDateOf(timestamp: Long): String = DateFormats.fullDate(timestamp)

// ===== Preview =====

private val previewSnapshots = (0 until 24).map { i ->
    BalanceSnapshot(
        timestamp = System.currentTimeMillis() - TimeUnit.DAYS.toMillis((23 - i).toLong()),
        balance = 40.0 + (i % 7) * 2.5
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
