package com.example.watchdog.ui.components

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.watchdog.R
import com.example.watchdog.data.model.ModelUsage
import com.example.watchdog.data.model.PlatformType
import com.example.watchdog.data.model.QuotaInfo
import com.example.watchdog.data.model.QuotaWindow
import com.example.watchdog.ui.theme.WatchDogTheme
import com.example.watchdog.ui.theme.balanceNumeral
import com.example.watchdog.ui.theme.brandBrush
import com.example.watchdog.ui.theme.brandOverlay
import com.example.watchdog.ui.theme.depletedBrush
import com.example.watchdog.ui.theme.onBrand
import com.example.watchdog.ui.theme.onBrandSecondary
import com.example.watchdog.ui.theme.warningBlendBrush
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.floor

private val onBrandDivider = onBrand.copy(alpha = 0.24f)

@Composable
fun PlatformQuotaCard(quotaInfo: QuotaInfo, modifier: Modifier = Modifier) {
    if (quotaInfo.isConfigured && quotaInfo.errorMessage == null) {
        BrandQuotaCard(quotaInfo, modifier)
    } else {
        NeutralQuotaCard(quotaInfo, modifier)
    }
}

// ===== 中性卡：未配置 / 查询异常 =====

@Composable
private fun NeutralQuotaCard(q: QuotaInfo, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlatformLogo(platform = q.platform)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    q.platform.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (q.isConfigured) stringResource(R.string.status_error)
                    else stringResource(R.string.status_not_configured),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (q.isConfigured) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.outline,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (q.errorMessage != null)
                    stringResource(R.string.quota_query_failed, q.errorMessage)
                else stringResource(R.string.quota_not_configured_hint),
                style = MaterialTheme.typography.bodySmall,
                color = if (q.errorMessage != null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ===== 品牌渐变卡：已配置且有数据 =====

@Composable
private fun BrandQuotaCard(q: QuotaInfo, modifier: Modifier = Modifier) {
    val fraction = remainingFraction(q)
    // 估算平台未填初始余额时 isAvailable=false，不应视为"耗尽"
    val estimatePending = q.isEstimate && !q.isAvailable
    val depleted = !q.isAvailable && !q.isEstimate
    val low = !depleted && !estimatePending && fraction != null && fraction < 0.2f

    val brush: Brush = when {
        depleted -> depletedBrush
        low -> warningBlendBrush(q.platform.brandColor)
        else -> brandBrush(q.platform.brandColor)
    }
    val statusText = when {
        estimatePending -> stringResource(R.string.status_estimate)
        depleted -> stringResource(R.string.status_depleted)
        low -> stringResource(R.string.status_low)
        else -> stringResource(R.string.status_normal)
    }

    Card(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth().background(brush)) {
            Column(modifier = Modifier.padding(16.dp)) {
                // 头部：Logo 水印 + 名称 + 状态药丸
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlatformLogo(
                        platform = q.platform,
                        backgroundColor = brandOverlay,
                        initialsColor = onBrand
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        q.platform.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = onBrand,
                        modifier = Modifier.weight(1f)
                    )
                    StatusPill(statusText)
                    if (q.isEstimate && !estimatePending) {
                        Spacer(modifier = Modifier.width(6.dp))
                        StatusPill(stringResource(R.string.status_estimate))
                    }
                    if (q.isStale) {
                        Spacer(modifier = Modifier.width(6.dp))
                        StatusPill(stringResource(R.string.status_cached))
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))

                when {
                    q.isSubscriptionMode -> SubscriptionContent(q)
                    estimatePending -> EstimatePendingContent(q)
                    else -> BalanceContent(q, fraction)
                }

                // 模型调用明细
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = onBrandDivider)
                ModelUsageSection(q)

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    stringResource(R.string.quota_updated_at, formatTime(q.lastUpdated)),
                    style = MaterialTheme.typography.labelSmall,
                    color = onBrandSecondary
                )
            }
        }
    }
}

@Composable
private fun StatusPill(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(brandOverlay)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = onBrand,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** 余额占比（0~1）：订阅模式取最低窗口剩余占比；GLM 等有限额的按 剩余/总量；无限额平台为 null */
private fun remainingFraction(q: QuotaInfo): Float? {
    q.lowestRemainingFraction?.let { return it.toFloat() }
    val limit = parseTokenNumber(q.monthlyLimit)
    if (limit > 0) {
        val remaining = parseTokenNumber(q.totalBalance)
        return (remaining.toFloat() / limit).coerceIn(0f, 1f)
    }
    return null
}

// ===== 按量付费余额模式 =====

@Composable
private fun BalanceContent(q: QuotaInfo, fraction: Float?) {
    val balanceLabel = if (q.platform == PlatformType.GLM)
        stringResource(R.string.quota_label_remaining_token)
    else stringResource(R.string.quota_label_total_balance)
    val secondaryLabel = when (q.platform) {
        PlatformType.GLM -> stringResource(R.string.quota_label_total_used)
        PlatformType.SILICONFLOW -> stringResource(R.string.quota_label_available_balance)
        else -> stringResource(R.string.quota_label_monthly_usage)
    }

    Text(balanceLabel, style = MaterialTheme.typography.labelMedium, color = onBrandSecondary)
    Spacer(modifier = Modifier.height(2.dp))
    Row(verticalAlignment = Alignment.Bottom) {
        Text(q.totalBalance, style = balanceNumeral, color = onBrand)
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            q.currency,
            style = MaterialTheme.typography.titleSmall,
            color = onBrandSecondary,
            modifier = Modifier.padding(bottom = 4.dp)
        )
    }

    if (fraction != null) {
        Spacer(modifier = Modifier.height(10.dp))
        BrandProgressBar(progress = fraction)
        if (q.platform == PlatformType.GLM) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                stringResource(R.string.quota_remaining_of_total, q.totalBalance, q.monthlyLimit, q.currency),
                style = MaterialTheme.typography.labelSmall,
                color = onBrandSecondary
            )
        }
    }

    Spacer(modifier = Modifier.height(10.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(secondaryLabel, style = MaterialTheme.typography.bodySmall, color = onBrandSecondary)
        Text("${q.monthlyUsage} ${q.currency}", style = MaterialTheme.typography.bodySmall, color = onBrand)
    }
}

// ===== 估算模式未填初始余额（火山方舟） =====

@Composable
private fun EstimatePendingContent(q: QuotaInfo) {
    Text(
        stringResource(R.string.quota_ark_need_initial),
        style = MaterialTheme.typography.bodyMedium,
        color = onBrand
    )
    Spacer(modifier = Modifier.height(10.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            stringResource(R.string.quota_label_monthly_usage),
            style = MaterialTheme.typography.bodySmall,
            color = onBrandSecondary
        )
        Text("${q.monthlyUsage} ${q.currency}", style = MaterialTheme.typography.bodySmall, color = onBrand)
    }
}

// ===== 订阅配额模式（Kimi Code） =====

@Composable
private fun SubscriptionContent(q: QuotaInfo) {
    q.planName?.let { plan ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                stringResource(R.string.quota_plan_label),
                style = MaterialTheme.typography.labelMedium,
                color = onBrandSecondary
            )
            Text(
                plan,
                style = MaterialTheme.typography.titleSmall,
                color = onBrand,
                fontWeight = FontWeight.SemiBold
            )
        }
    }

    q.quotaWindows.forEach { window ->
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                window.name ?: stringResource(R.string.quota_window_default_name),
                style = MaterialTheme.typography.bodySmall,
                color = onBrand,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(
                    R.string.quota_window_used_remaining,
                    formatQuotaNumber(window.used),
                    formatQuotaNumber(window.remaining)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = onBrandSecondary
            )
        }
        val limit = window.limit
        val used = window.used
        if (limit != null && limit > 0 && used != null) {
            Spacer(modifier = Modifier.height(4.dp))
            BrandProgressBar(progress = (used / limit).toFloat().coerceIn(0f, 1f))
        }
    }

    val nextReset = q.quotaWindows.mapNotNull { it.resetTime }.minOrNull()
    val nextExpiry = q.quotaWindows.mapNotNull { it.expiresAt }.minOrNull()
    if (nextReset != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            stringResource(R.string.quota_reset_at, formatDateTime(nextReset)),
            style = MaterialTheme.typography.labelSmall,
            color = onBrandSecondary
        )
    }
    if (nextExpiry != null) {
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            stringResource(R.string.quota_expires_at, formatDateTime(nextExpiry)),
            style = MaterialTheme.typography.labelSmall,
            color = onBrandSecondary
        )
    }
    q.boosterInfo?.let { booster ->
        Spacer(modifier = Modifier.height(2.dp))
        Text(booster, style = MaterialTheme.typography.labelSmall, color = onBrandSecondary)
    }
}

// ===== 模型调用明细 =====

@Composable
private fun ModelUsageSection(q: QuotaInfo) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (q.hasModelUsage)
                stringResource(R.string.quota_usage_details_count, q.modelUsages.size)
            else stringResource(R.string.quota_usage_details),
            style = MaterialTheme.typography.labelMedium,
            color = onBrand,
            modifier = Modifier.weight(1f)
        )
        if (q.hasModelUsage) {
            Text(
                text = stringResource(
                    R.string.quota_usage_summary,
                    formatNumber(q.totalRequestCount),
                    formatNumber(q.totalTokensUsed)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = onBrandSecondary
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            if (expanded) "▲" else "▼",
            style = MaterialTheme.typography.labelSmall,
            color = onBrand
        )
    }
    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(), exit = shrinkVertically()
    ) {
        Column {
            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider(thickness = 0.5.dp, color = onBrandDivider)
            Spacer(modifier = Modifier.height(8.dp))
            if (q.hasModelUsage) {
                val maxTokens = q.modelUsages.maxOf { it.totalTokens }.coerceAtLeast(1)
                q.modelUsages.forEach { mu -> ModelUsageRow(mu, maxTokens) }
            } else {
                ModelUsageEmptyHint(q.platform)
            }
        }
    }
}

/** 每模型一行：模型名 + 细进度条（按 tokens 占本卡最大模型比例）+ 数值标签 */
@Composable
private fun ModelUsageRow(mu: ModelUsage, maxTokens: Long) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                mu.modelName,
                style = MaterialTheme.typography.bodySmall,
                color = onBrand,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                stringResource(R.string.quota_model_row_label, formatNumber(mu.totalTokens), mu.requestCount),
                style = MaterialTheme.typography.labelSmall,
                color = onBrandSecondary
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { (mu.totalTokens.toFloat() / maxTokens).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = onBrand,
            trackColor = brandOverlay
        )
    }
}

/** 品牌渐变卡上的白色半透进度条（轨道为白 12% 遮罩） */
@Composable
private fun BrandProgressBar(progress: Float) {
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
        color = onBrand,
        trackColor = brandOverlay
    )
}

@Composable
private fun ModelUsageEmptyHint(platform: PlatformType) {
    val hint = when (platform) {
        PlatformType.DEEPSEEK -> stringResource(R.string.usage_hint_deepseek)
        PlatformType.KIMI -> stringResource(R.string.usage_hint_kimi)
        PlatformType.GLM -> stringResource(R.string.usage_hint_glm)
        PlatformType.SILICONFLOW -> stringResource(R.string.usage_hint_siliconflow)
        PlatformType.VOLCENGINE_ARK -> stringResource(R.string.usage_hint_volcengine)
        PlatformType.KIMI_CODE -> stringResource(R.string.usage_hint_kimi_code)
    }
    Text(
        text = hint,
        style = MaterialTheme.typography.bodySmall,
        color = onBrandSecondary
    )
}

// ===== 工具函数 =====

private fun parseTokenNumber(f: String): Long = try {
    when {
        f.endsWith("B", true) -> (f.dropLast(1).toDouble() * 1_000_000_000).toLong()
        f.endsWith("M", true) -> (f.dropLast(1).toDouble() * 1_000_000).toLong()
        f.endsWith("K", true) -> (f.dropLast(1).toDouble() * 1_000).toLong()
        else -> f.toLongOrNull() ?: 0L
    }
} catch (_: Exception) { 0L }

private fun formatNumber(n: Long): String = when {
    n >= 1_000_000_000 -> String.format("%.1fB", n / 1_000_000_000.0)
    n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format("%.1fK", n / 1_000.0)
    else -> n.toString()
}

/** 订阅窗口数值格式化：大数值缩写，小数值去掉多余小数，未知显示 "-" */
private fun formatQuotaNumber(value: Double?): String {
    if (value == null) return "-"
    if (value >= 1000) return formatNumber(value.toLong())
    return if (value == floor(value)) String.format("%.0f", value) else String.format("%.2f", value)
}

private fun formatTime(t: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(t))

private fun formatDateTime(t: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(t))

// ===== Preview =====

private val previewQuotaNormal = QuotaInfo(
    platform = PlatformType.DEEPSEEK,
    isAvailable = true,
    isConfigured = true,
    totalBalance = "36.50",
    monthlyUsage = "12.34",
    currency = "CNY",
    modelUsages = listOf(
        ModelUsage("deepseek-chat", requestCount = 128, totalTokens = 1_250_000),
        ModelUsage("deepseek-reasoner", requestCount = 12, totalTokens = 320_000)
    )
)

private val previewQuotaSubscription = QuotaInfo(
    platform = PlatformType.KIMI_CODE,
    isAvailable = true,
    isConfigured = true,
    planName = "Andante 套餐",
    quotaWindows = listOf(
        QuotaWindow("5小时", used = 42.0, remaining = 58.0, limit = 100.0,
            resetTime = System.currentTimeMillis() + 3 * 3600_000L),
        QuotaWindow("周", used = 800.0, remaining = 200.0, limit = 1000.0,
            expiresAt = System.currentTimeMillis() + 5 * 86400_000L)
    ),
    boosterInfo = "Booster：高峰期 2 倍速率"
)

private val previewQuotaEstimate = QuotaInfo(
    platform = PlatformType.VOLCENGINE_ARK,
    isAvailable = true,
    isConfigured = true,
    isEstimate = true,
    totalBalance = "88.00",
    monthlyUsage = "12.00",
    currency = "CNY"
)

private val previewQuotaDepleted = QuotaInfo(
    platform = PlatformType.SILICONFLOW,
    isAvailable = false,
    isConfigured = true,
    totalBalance = "0.00",
    monthlyUsage = "56.78"
)

private val previewQuotaNotConfigured = QuotaInfo.notConfigured(PlatformType.GLM)

@Composable
private fun QuotaCardPreviewContent() {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PlatformQuotaCard(previewQuotaNormal)
        PlatformQuotaCard(previewQuotaSubscription)
        PlatformQuotaCard(previewQuotaEstimate)
        PlatformQuotaCard(previewQuotaDepleted)
        PlatformQuotaCard(previewQuotaNotConfigured)
    }
}

@Preview(name = "额度卡片-浅色", showBackground = true)
@Composable
private fun PlatformQuotaCardPreviewLight() {
    WatchDogTheme { QuotaCardPreviewContent() }
}

@Preview(name = "额度卡片-深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PlatformQuotaCardPreviewDark() {
    WatchDogTheme { QuotaCardPreviewContent() }
}
