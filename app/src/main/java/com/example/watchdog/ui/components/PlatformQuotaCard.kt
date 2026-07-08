package com.example.watchdog.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.watchdog.data.model.ModelUsage
import com.example.watchdog.data.model.PlatformType
import com.example.watchdog.data.model.QuotaInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PlatformQuotaCard(quotaInfo: QuotaInfo, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                !quotaInfo.isConfigured -> MaterialTheme.colorScheme.surfaceVariant
                quotaInfo.isAvailable -> MaterialTheme.colorScheme.surface
                else -> MaterialTheme.colorScheme.errorContainer
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            PlatformHeader(quotaInfo)
            Spacer(modifier = Modifier.height(12.dp))

            if (!quotaInfo.isConfigured) NotConfiguredContent()
            else if (quotaInfo.errorMessage != null) ErrorContent(quotaInfo.errorMessage)
            else QuotaContent(quotaInfo)
        }
    }
}

// ===== Header =====

@Composable
private fun PlatformHeader(quotaInfo: QuotaInfo) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PlatformLogo(platform = quotaInfo.platform)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(quotaInfo.platform.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        val (s, c) = when {
            !quotaInfo.isConfigured -> "未配置" to MaterialTheme.colorScheme.outline
            quotaInfo.errorMessage != null -> "异常" to MaterialTheme.colorScheme.error
            quotaInfo.isAvailable -> "正常" to quotaInfo.platform.brandColor
            else -> "耗尽" to MaterialTheme.colorScheme.error
        }
        Text(s, style = MaterialTheme.typography.labelSmall, color = c, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun NotConfiguredContent() {
    Text("请先配置API Key以查看额度信息", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
}

@Composable
private fun ErrorContent(msg: String) {
    Text("查询失败: $msg", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

// ===== 数据主体 =====

@Composable
private fun QuotaContent(q: QuotaInfo) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        DataRow(label = if (q.platform == PlatformType.GLM) "剩余Token" else "总余额",
            value = q.totalBalance, unit = q.currency, bold = true)

        Spacer(modifier = Modifier.height(6.dp))
        DataRow(label = if (q.platform == PlatformType.GLM) "累计已用" else "本月用量",
            value = q.monthlyUsage, unit = q.currency, bold = false)

        // GLM 进度条
        if (q.platform == PlatformType.GLM && q.monthlyLimit != "0") {
            Spacer(modifier = Modifier.height(10.dp))
            GlmBar(q)
        }

        // 模型用量明细（可展开）
        if (q.hasModelUsage) {
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "调用明细 (${q.modelUsages.size}项)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "共 ${formatNumber(q.totalRequestCount)}次调用  ${formatNumber(q.totalTokensUsed)} Tokens",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    if (expanded) "▲" else "▼",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { expanded = !expanded }
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(), exit = shrinkVertically()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(4.dp))
                    HorizontalDivider(thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(8.dp))
                    // 表头
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text("模型", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.weight(2f))
                        Text("调用", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.weight(1f))
                        Text("Tokens", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.weight(1.2f))
                        Text("费用", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.weight(1f))
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    q.modelUsages.forEach { mu ->
                        ModelUsageRow(mu)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Text("更新: ${formatTime(q.lastUpdated)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun ModelUsageRow(mu: ModelUsage) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(mu.modelName, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(2f))
        Text("${mu.requestCount}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(formatNumber(mu.totalTokens), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.2f))
        Text(mu.cost, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun GlmBar(q: QuotaInfo) {
    val used = parseTokenNumber(q.monthlyUsage)
    val limit = parseTokenNumber(q.monthlyLimit)
    val p = if (limit > 0) (used.toFloat() / limit).coerceIn(0f, 1f) else 0f
    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth(),
        color = if (p > 0.8f) MaterialTheme.colorScheme.error else q.platform.brandColor,
        trackColor = q.platform.brandColor.copy(alpha = 0.12f))
    Spacer(modifier = Modifier.height(4.dp))
    Text("剩余: ${q.totalBalance} / 总量: ${q.monthlyLimit} ${q.currency}",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DataRow(label: String, value: String, unit: String, bold: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("$value $unit", style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
    }
}

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

private fun formatTime(t: Long): String = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(t))
