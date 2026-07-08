package com.example.watchdog.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.watchdog.data.model.PlatformType
import com.example.watchdog.data.model.QuotaInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PlatformQuotaCard(
    quotaInfo: QuotaInfo,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
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
            // 第一行：Logo + 平台名 + 状态
            PlatformHeader(quotaInfo)
            Spacer(modifier = Modifier.height(12.dp))

            if (!quotaInfo.isConfigured) {
                NotConfiguredContent()
            } else if (quotaInfo.errorMessage != null) {
                ErrorContent(quotaInfo.errorMessage)
            } else {
                QuotaContent(quotaInfo)
            }
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
            Text(
                text = quotaInfo.platform.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        // 状态标签
        val (statusText, statusColor) = when {
            !quotaInfo.isConfigured -> "未配置" to MaterialTheme.colorScheme.outline
            quotaInfo.errorMessage != null -> "异常" to MaterialTheme.colorScheme.error
            quotaInfo.isAvailable -> "正常" to quotaInfo.platform.brandColor
            else -> "耗尽" to MaterialTheme.colorScheme.error
        }
        Text(
            text = statusText,
            style = MaterialTheme.typography.labelSmall,
            color = statusColor,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun NotConfiguredContent() {
    Text(
        text = "请先配置API Key以查看额度信息",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline
    )
}

@Composable
private fun ErrorContent(errorMessage: String) {
    Text(
        text = "查询失败: $errorMessage",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error
    )
}

// ===== 数据主体 =====

@Composable
private fun QuotaContent(quotaInfo: QuotaInfo) {
    Column {
        // 总余额
        DataRow(
            label = labelForBalance(quotaInfo.platform),
            value = quotaInfo.totalBalance,
            unit = quotaInfo.currency,
            valueColor = MaterialTheme.colorScheme.onSurface
        )

        // 本月用量（所有平台都显示）
        Spacer(modifier = Modifier.height(6.dp))
        DataRow(
            label = labelForUsage(quotaInfo.platform),
            value = quotaInfo.monthlyUsage,
            unit = quotaInfo.currency,
            valueColor = if (quotaInfo.monthlyUsage != "0" && quotaInfo.monthlyUsage != "0.00")
                MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant
        )

        // GLM 专用：使用进度条
        if (quotaInfo.platform == PlatformType.GLM &&
            quotaInfo.monthlyLimit != "0"
        ) {
            Spacer(modifier = Modifier.height(10.dp))
            GlmProgressBar(quotaInfo)
        }

        // 更新时间
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "更新: ${formatTime(quotaInfo.lastUpdated)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
private fun GlmProgressBar(quotaInfo: QuotaInfo) {
    val usedRaw = parseTokenNumber(quotaInfo.monthlyUsage)
    val limitRaw = parseTokenNumber(quotaInfo.monthlyLimit)
    val progress = if (limitRaw > 0) (usedRaw.toFloat() / limitRaw).coerceIn(0f, 1f) else 0f

    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.fillMaxWidth(),
        color = if (progress > 0.8f) MaterialTheme.colorScheme.error else quotaInfo.platform.brandColor,
        trackColor = quotaInfo.platform.brandColor.copy(alpha = 0.12f)
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = "剩余: ${quotaInfo.totalBalance} / 总量: ${quotaInfo.monthlyLimit} ${quotaInfo.currency}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun DataRow(
    label: String,
    value: String,
    unit: String,
    valueColor: androidx.compose.ui.graphics.Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "$value $unit",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = valueColor
        )
    }
}

// ===== 工具函数 =====

private fun labelForBalance(platform: PlatformType): String {
    return when (platform) {
        PlatformType.DEEPSEEK, PlatformType.KIMI, PlatformType.SILICONFLOW -> "总余额"
        PlatformType.GLM -> "剩余Token"
    }
}

private fun labelForUsage(platform: PlatformType): String {
    return when (platform) {
        PlatformType.DEEPSEEK, PlatformType.KIMI, PlatformType.SILICONFLOW -> "本月消耗"
        PlatformType.GLM -> "累计已用"
    }
}

private fun parseTokenNumber(formatted: String): Long {
    return try {
        when {
            formatted.endsWith("B", ignoreCase = true) ->
                (formatted.dropLast(1).toDouble() * 1_000_000_000).toLong()
            formatted.endsWith("M", ignoreCase = true) ->
                (formatted.dropLast(1).toDouble() * 1_000_000).toLong()
            formatted.endsWith("K", ignoreCase = true) ->
                (formatted.dropLast(1).toDouble() * 1_000).toLong()
            else -> formatted.toLongOrNull() ?: 0L
        }
    } catch (e: Exception) { 0L }
}

private fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
