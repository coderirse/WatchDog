package io.github.coderirse.watchdog.ui.components

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.coderirse.watchdog.R
import io.github.coderirse.watchdog.data.model.ModelUsage
import io.github.coderirse.watchdog.data.model.MonthlyUsageSource
import io.github.coderirse.watchdog.data.model.PlatformType
import io.github.coderirse.watchdog.data.model.QuotaInfo
import io.github.coderirse.watchdog.data.model.QuotaWindow
import io.github.coderirse.watchdog.ui.theme.LocalBrandOverlay
import io.github.coderirse.watchdog.ui.theme.LocalOnBrand
import io.github.coderirse.watchdog.ui.theme.LocalOnBrandSecondary
import io.github.coderirse.watchdog.ui.theme.WatchDogTheme
import io.github.coderirse.watchdog.ui.theme.DepletedBase
import io.github.coderirse.watchdog.ui.theme.brandBrush
import io.github.coderirse.watchdog.ui.theme.darkened
import io.github.coderirse.watchdog.ui.theme.depletedBrush
import io.github.coderirse.watchdog.ui.theme.onBrandFor
import io.github.coderirse.watchdog.ui.theme.secondaryNumeral
import io.github.coderirse.watchdog.ui.theme.warningBlendBrush
import io.github.coderirse.watchdog.util.DateFormats
import io.github.coderirse.watchdog.util.FormatUtils
import java.util.Locale
import kotlin.math.floor

/**
 * 单个平台的额度卡。已配置且无异常 → 品牌渐变卡；未配置/查询异常 → 中性卡。
 *
 * 本次改进要点（都围绕"避免误读"）：
 * 1. 进度条一律带明确文字前缀（"剩余 x%"/"已用 x%"）：此前同一组件在余额场景传剩余占比、
 *    在订阅窗口传已用占比、在模型明细里传"相对最大模型的占比"，三种相反语义外观完全相同；
 * 2. 数值未知时显示占位符"—"而非 0：假 0 会被读成"余额/用量就是 0"；
 * 3. 本地推算的"本月用量"标注（估算），与火山方舟的估算口径统一；
 * 4. "调用明细"展开行补 role/stateDescription 与 48dp 触控高度。
 */
@Composable
fun PlatformQuotaCard(
    quotaInfo: QuotaInfo,
    modifier: Modifier = Modifier,
    onRelogin: ((PlatformType) -> Unit)? = null
) {
    if (quotaInfo.isConfigured && quotaInfo.errorMessage == null) {
        BrandQuotaCard(quotaInfo, modifier)
    } else {
        NeutralQuotaCard(quotaInfo, modifier, onRelogin)
    }
}

// ===== 中性卡：未配置 / 查询异常 =====

@Composable
private fun NeutralQuotaCard(
    q: QuotaInfo,
    modifier: Modifier = Modifier,
    onRelogin: ((PlatformType) -> Unit)? = null
) {
    Card(
        modifier = modifier.fillMaxWidth().animateContentSize(),
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
            // 需要重新登录网页会话时给明确的按钮（而非让用户去设置页里找）
            if (q.needsRelogin && onRelogin != null) {
                Spacer(modifier = Modifier.height(10.dp))
                TextButton(onClick = { onRelogin(q.platform) }) {
                    Text(stringResource(R.string.quota_relogin_button))
                }
            }
            // 控制台抓取失败的诊断码（如 HTTP 429/200），便于定位 WAF/接口变更
            q.consoleDiag?.let { diag ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = diag,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
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
    // 零余额（P1）：可用数据可信且余额恰为 0 —— 之前落在 else 分支显示"正常"药丸 +
    // 40sp 巨大"0.00"，全卡最抢眼的信息是一个无信息量的零
    val zeroBalance = !q.isSubscriptionMode && !estimatePending && !depleted &&
        q.totalBalance.toDoubleOrNull() == 0.0
    val low = !depleted && !estimatePending && !zeroBalance &&
        fraction != null && fraction < 0.2f

    val brush: Brush = when {
        depleted -> depletedBrush
        low -> warningBlendBrush(q.platform.visual.brandColor)
        else -> brandBrush(q.platform.visual.brandColor)
    }
    val statusText = when {
        estimatePending -> stringResource(R.string.status_estimate)
        depleted -> stringResource(R.string.status_depleted)
        zeroBalance -> stringResource(R.string.status_zero_balance)
        low -> stringResource(R.string.status_low)
        else -> stringResource(R.string.status_normal)
    }
    // 文字色按渐变起始色亮度自适应：亮色系品牌（绿/橙）上改用深色文字保证 WCAG 对比度
    val baseColor = when {
        depleted -> DepletedBase
        low -> q.platform.visual.brandColor.darkened(0.85f)
        else -> q.platform.visual.brandColor
    }
    val onBrand = onBrandFor(baseColor)

    CompositionLocalProvider(
        LocalOnBrand provides onBrand,
        LocalOnBrandSecondary provides onBrand.copy(alpha = 0.85f),
        LocalBrandOverlay provides onBrand.copy(alpha = 0.12f)
    ) {
        Card(
            modifier = modifier.fillMaxWidth().animateContentSize(),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent)
        ) {
            Box(modifier = Modifier.fillMaxWidth().background(brush)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // 头部：Logo 水印 + 名称 + 状态药丸
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PlatformLogo(
                            platform = q.platform,
                            backgroundColor = LocalBrandOverlay.current,
                            initialsColor = LocalOnBrand.current
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            q.platform.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = LocalOnBrand.current,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
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
                        zeroBalance -> ZeroBalanceContent(q)
                        else -> BalanceContent(q, fraction)
                    }

                    if (!estimatePending) {
                        // 模型调用明细（可展开）
                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = LocalOnBrand.current.copy(alpha = 0.24f))
                        ModelUsageSection(q)

                        // 控制台抓取失败诊断（已配置会话但数据回退官方接口时提示失败原因）
                        q.consoleDiag?.let { diag ->
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = diag,
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalOnBrandSecondary.current
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        // 数据来源（网页控制台等非官方接口）降级为脚注小字，不再占用头部药丸
                        Text(
                            text = listOfNotNull(
                                q.dataSourceLabel,
                                stringResource(R.string.quota_updated_at, DateFormats.dateTime(q.lastUpdated))
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalOnBrandSecondary.current
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(text: String) {
    val onBrand = LocalOnBrand.current
    val brandOverlay = LocalBrandOverlay.current
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
    val limit = FormatUtils.parseTokenNumber(q.monthlyLimit)
    if (limit > 0) {
        val remaining = FormatUtils.parseTokenNumber(q.totalBalance)
        return (remaining.toFloat() / limit).coerceIn(0f, 1f)
    }
    return null
}

/** 余额文本：空/不可解析时显示占位符，避免把"没有数据"渲染成"0.00"。 */
private fun balanceText(q: QuotaInfo): String =
    q.totalBalance.toDoubleOrNull()?.let { String.format(Locale.US, "%.2f", it) }
        ?: q.totalBalance.takeIf { it.isNotBlank() }
        ?: "—"

/** 百分比取整文案（进度条前缀用）。 */
private fun percentText(fraction: Float): String =
    String.format(Locale.US, "%.0f", fraction * 100f)

// ===== 按量付费余额模式 =====

@Composable
private fun BalanceContent(q: QuotaInfo, fraction: Float?) {
    val onBrand = LocalOnBrand.current
    val onBrandSecondary = LocalOnBrandSecondary.current
    val balanceLabel = if (q.platform == PlatformType.GLM)
        stringResource(R.string.quota_label_remaining_token)
    else stringResource(R.string.quota_label_total_balance)
    val (monthlyLabel, monthlyValue) = monthlyUsageParts(q)

    Text(balanceLabel, style = MaterialTheme.typography.labelMedium, color = onBrandSecondary)
    Spacer(modifier = Modifier.height(2.dp))
    Row(verticalAlignment = Alignment.Bottom) {
        BalanceText(text = balanceText(q), color = onBrand)
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            q.currency,
            style = MaterialTheme.typography.titleSmall,
            color = onBrandSecondary,
            modifier = Modifier.padding(bottom = 6.dp)
        )
    }

    if (fraction != null) {
        Spacer(modifier = Modifier.height(10.dp))
        // 进度条语义前缀：这里画的是"剩余"占比，必须写清楚
        BrandProgressBar(
            progress = fraction,
            caption = stringResource(R.string.quota_progress_remaining, percentText(fraction))
        )
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
        Text(monthlyLabel, style = MaterialTheme.typography.bodySmall, color = onBrandSecondary)
        Text(
            text = monthlyValue.ifBlank { stringResource(R.string.quota_value_unknown) } +
                if (monthlyValue.isBlank()) "" else " ${q.currency}",
            style = MaterialTheme.typography.bodySmall,
            color = onBrand
        )
    }
}

// ===== 零余额（按量付费、数据可信但余额为 0） =====

/**
 * 零余额内容：不再用 40sp 大号"0.00"抢占视觉焦点（零没有信息量），
 * 改为 20sp 数字 + 明确的行动指引；本月用量上移为主信息。
 * 品牌渐变保留（品牌识别不变），状态药丸已由"正常"改为"余额为 0"。
 */
@Composable
private fun ZeroBalanceContent(q: QuotaInfo) {
    val onBrand = LocalOnBrand.current
    val onBrandSecondary = LocalOnBrandSecondary.current
    val balanceLabel = if (q.platform == PlatformType.GLM)
        stringResource(R.string.quota_label_remaining_token)
    else stringResource(R.string.quota_label_total_balance)
    val (monthlyLabel, monthlyValue) = monthlyUsageParts(q)

    Text(balanceLabel, style = MaterialTheme.typography.labelMedium, color = onBrandSecondary)
    Spacer(modifier = Modifier.height(2.dp))
    Row(verticalAlignment = Alignment.Bottom) {
        Text("0.00", style = secondaryNumeral, color = onBrand)
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            q.currency,
            style = MaterialTheme.typography.titleSmall,
            color = onBrandSecondary,
            modifier = Modifier.padding(bottom = 3.dp)
        )
    }
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        stringResource(R.string.quota_zero_balance_hint),
        style = MaterialTheme.typography.bodySmall,
        color = onBrand
    )
    Spacer(modifier = Modifier.height(10.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(monthlyLabel, style = MaterialTheme.typography.bodySmall, color = onBrandSecondary)
        Text(monthlyValue, style = MaterialTheme.typography.bodySmall, color = onBrand)
    }
}

/** 本月用量行的（标签, 数值）取值逻辑，BalanceContent / ZeroBalanceContent 共用。 */
@Composable
private fun monthlyUsageParts(q: QuotaInfo): Pair<String, String> {
    val monthlyLabel = when {
        q.platform == PlatformType.GLM -> stringResource(R.string.quota_label_total_used)
        q.platform == PlatformType.SILICONFLOW -> stringResource(R.string.quota_label_available_balance)
        q.isMonthlyUsageEstimated -> stringResource(R.string.quota_label_monthly_usage_estimated)
        else -> stringResource(R.string.quota_label_monthly_usage)
    }
    // 硅基流动展示专用 availableBalance 字段，不再复用 monthlyUsage 造成语义错位
    val monthlyValue = if (q.platform == PlatformType.SILICONFLOW) {
        q.availableBalance ?: q.monthlyUsage
    } else {
        q.monthlyUsage
    }
    val rendered = monthlyValue.ifBlank { stringResource(R.string.quota_value_unknown) } +
        if (monthlyValue.isBlank()) "" else " ${q.currency}"
    return monthlyLabel to rendered
}

// ===== 估算模式未填初始余额（火山方舟） =====

@Composable
private fun EstimatePendingContent(q: QuotaInfo) {
    val onBrand = LocalOnBrand.current
    val onBrandSecondary = LocalOnBrandSecondary.current
    Text(
        stringResource(R.string.quota_ark_need_initial),
        style = MaterialTheme.typography.bodyMedium,
        color = onBrand
    )
    Spacer(modifier = Modifier.height(10.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            stringResource(R.string.quota_label_monthly_usage_estimated),
            style = MaterialTheme.typography.bodySmall,
            color = onBrandSecondary
        )
        Text(
            "${q.monthlyUsage.ifBlank { "0.00" }} ${q.currency}",
            style = MaterialTheme.typography.bodySmall,
            color = onBrand
        )
    }
}

// ===== 订阅配额模式（Kimi Code / MiMo Token Plan） =====

@Composable
private fun SubscriptionContent(q: QuotaInfo) {
    val onBrand = LocalOnBrand.current
    val onBrandSecondary = LocalOnBrandSecondary.current
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
            val usedFraction = (used / limit).toFloat().coerceIn(0f, 1f)
            Spacer(modifier = Modifier.height(4.dp))
            // 同一组件的语义前缀：订阅窗口画的是"已用"占比
            BrandProgressBar(
                progress = usedFraction,
                caption = stringResource(R.string.quota_progress_used, percentText(usedFraction))
            )
        }
    }

    val nextReset = q.quotaWindows.mapNotNull { it.resetTime }.minOrNull()
    val nextExpiry = q.quotaWindows.mapNotNull { it.expiresAt }.minOrNull()
    if (nextReset != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            stringResource(R.string.quota_reset_at, DateFormats.dateTime(nextReset)),
            style = MaterialTheme.typography.labelSmall,
            color = onBrandSecondary
        )
    }
    if (nextExpiry != null) {
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            stringResource(R.string.quota_expires_at, DateFormats.dateTime(nextExpiry)),
            style = MaterialTheme.typography.labelSmall,
            color = onBrandSecondary
        )
    }
    q.boosterInfo?.let { booster ->
        Spacer(modifier = Modifier.height(2.dp))
        Text(booster, style = MaterialTheme.typography.labelSmall, color = onBrandSecondary)
    }

    // 订阅模式平台若同时探测到账户余额（如 MiMo 即按量付费 + Token Plan 并存），附带展示一行
    val accountBalance = q.totalBalance.toDoubleOrNull()
    if (accountBalance != null && accountBalance > 0 && q.currency.equals("CNY", true)) {
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.quota_label_account_balance),
                style = MaterialTheme.typography.bodySmall,
                color = onBrandSecondary
            )
            Text(
                balanceText(q) + " " + q.currency,
                style = MaterialTheme.typography.bodySmall,
                color = onBrand,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

// ===== 模型调用明细 =====

@Composable
private fun ModelUsageSection(q: QuotaInfo) {
    val onBrand = LocalOnBrand.current
    val onBrandSecondary = LocalOnBrandSecondary.current
    var expanded by remember { mutableStateOf(false) }
    val expandedLabel = stringResource(
        if (expanded) R.string.quota_details_expanded else R.string.quota_details_collapsed
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 48dp 最小触控高度（原实现约 24dp，低于 Material 规范）
            .defaultMinSize(minHeight = 48.dp)
            .clickable { expanded = !expanded }
            .semantics {
                role = Role.Button
                stateDescription = expandedLabel
            },
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
                    FormatUtils.formatNumber(q.totalRequestCount),
                    FormatUtils.formatNumber(q.totalTokensUsed)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = onBrandSecondary
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        val chevronRotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = onBrand,
            modifier = Modifier.size(20.dp).rotate(chevronRotation)
        )
    }
    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(), exit = shrinkVertically()
    ) {
        Column {
            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider(thickness = 0.5.dp, color = onBrand.copy(alpha = 0.24f))
            Spacer(modifier = Modifier.height(8.dp))
            if (q.hasModelUsage) {
                val maxTokens = q.modelUsages.maxOf { it.totalTokens }.coerceAtLeast(1)
                val multiModel = q.modelUsages.size > 1
                q.modelUsages.forEach { mu -> ModelUsageRow(mu, maxTokens, multiModel) }
            } else {
                ModelUsageEmptyHint(q.platform)
            }
        }
    }
}

/**
 * 每模型一行：模型名 + 细进度条 + 数值标签。
 * 进度条含义为"该模型占本卡最大模型的占比"，与余额/已用占比不同，
 * 多模型时补一行说明，避免与卡片上方的余额进度条混淆。
 */
@Composable
private fun ModelUsageRow(mu: ModelUsage, maxTokens: Long, showShareCaption: Boolean) {
    val onBrand = LocalOnBrand.current
    val onBrandSecondary = LocalOnBrandSecondary.current
    val brandOverlay = LocalBrandOverlay.current
    val share = (mu.totalTokens.toFloat() / maxTokens).coerceIn(0f, 1f)

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        // 模型名与数值分行：大字号/长模型名下同行会互相挤压
        Text(
            mu.modelName,
            style = MaterialTheme.typography.bodySmall,
            color = onBrand,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            stringResource(R.string.quota_model_row_label, FormatUtils.formatNumber(mu.totalTokens), mu.requestCount) +
                if (showShareCaption) {
                    " · " + stringResource(R.string.quota_progress_share, percentText(share))
                } else "",
            style = MaterialTheme.typography.labelSmall,
            color = onBrandSecondary
        )
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { share },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = onBrand,
            trackColor = brandOverlay
        )
    }
}

/**
 * 品牌渐变卡上的半透明白进度条。
 * [caption] 为必要的语义前缀（剩余/已用 x%）：同一视觉承载不同含义时，
 * 必须由文字消歧，否则用户只能靠猜。
 */
@Composable
private fun BrandProgressBar(progress: Float, caption: String) {
    val onBrand = LocalOnBrand.current
    val onBrandSecondary = LocalOnBrandSecondary.current
    val brandOverlay = LocalBrandOverlay.current
    Column(modifier = Modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = onBrand,
            trackColor = brandOverlay
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = caption,
            style = MaterialTheme.typography.labelSmall,
            color = onBrandSecondary
        )
    }
}

@Composable
private fun ModelUsageEmptyHint(platform: PlatformType) {
    val onBrandSecondary = LocalOnBrandSecondary.current
    val hint = when (platform) {
        PlatformType.DEEPSEEK -> stringResource(R.string.usage_hint_deepseek)
        PlatformType.KIMI -> stringResource(R.string.usage_hint_kimi)
        PlatformType.GLM -> stringResource(R.string.usage_hint_glm)
        PlatformType.SILICONFLOW -> stringResource(R.string.usage_hint_siliconflow)
        PlatformType.VOLCENGINE_ARK -> stringResource(R.string.usage_hint_volcengine)
        PlatformType.KIMI_CODE -> stringResource(R.string.usage_hint_kimi_code)
        PlatformType.MIMO -> stringResource(R.string.usage_hint_mimo)
    }
    Text(
        text = hint,
        style = MaterialTheme.typography.bodySmall,
        color = onBrandSecondary
    )
}

// ===== 工具函数 =====

/** 订阅窗口数值格式化：大数值缩写，小数值去掉多余小数，未知显示 "-" */
private fun formatQuotaNumber(value: Double?): String {
    if (value == null) return "-"
    if (value >= 1000) return FormatUtils.formatNumber(value.toLong())
    return if (value == floor(value)) String.format(Locale.US, "%.0f", value)
    else String.format(Locale.US, "%.2f", value)
}

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

private val previewQuotaEstimatedUsage = QuotaInfo(
    platform = PlatformType.KIMI,
    isAvailable = true,
    isConfigured = true,
    totalBalance = "18.20",
    monthlyUsage = "5.60",
    monthlyUsageSource = MonthlyUsageSource.LOCAL_ESTIMATE
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

private val previewQuotaMimo = QuotaInfo(
    platform = PlatformType.MIMO,
    isAvailable = true,
    isConfigured = true,
    planName = "Pro 套餐",
    quotaWindows = listOf(
        QuotaWindow("月度", used = 6200.0, remaining = 3800.0, limit = 10000.0,
            expiresAt = System.currentTimeMillis() + 18 * 86400_000L)
    ),
    totalBalance = "36.50",
    currency = "CNY",
    boosterInfo = "月度续订 · Credits 额度 10000",
    dataSourceLabel = "网页控制台"
)

@Composable
private fun QuotaCardPreviewContent() {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PlatformQuotaCard(previewQuotaNormal)
        PlatformQuotaCard(previewQuotaEstimatedUsage)
        PlatformQuotaCard(previewQuotaSubscription)
        PlatformQuotaCard(previewQuotaMimo)
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
