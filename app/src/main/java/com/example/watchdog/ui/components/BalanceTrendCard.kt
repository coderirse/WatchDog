package com.example.watchdog.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.watchdog.R
import com.example.watchdog.data.model.BalanceSnapshot
import java.util.Locale

/**
 * 余额趋势卡：展示按 CNY 计价的平台总余额随时间变化的折线图。
 */
@Composable
fun BalanceTrendCard(
    history: List<BalanceSnapshot>,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.trend_title),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (history.size < 2) {
                Text(
                    text = stringResource(R.string.trend_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                BalanceTrendChart(
                    history = history,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                TrendSummaryRow(history)
            }
        }
    }
}

@Composable
private fun BalanceTrendChart(history: List<BalanceSnapshot>, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    val points = history.sortedBy { it.timestamp }
    val minBalance = points.minOf { it.balance }
    val maxBalance = points.maxOf { it.balance }
    val range = (maxBalance - minBalance).coerceAtLeast(0.01)

    Canvas(modifier = modifier) {
        val left = 4.dp.toPx()
        val top = 4.dp.toPx()
        val bottom = 4.dp.toPx()
        val chartW = size.width - left * 2
        val chartH = size.height - top - bottom

        fun xOf(index: Int): Float =
            if (points.size <= 1) left + chartW / 2f
            else left + chartW * index / (points.size - 1).toFloat()

        fun yOf(balance: Double): Float =
            top + chartH * (1f - ((balance - minBalance) / range).toFloat())

        if (points.size >= 2) {
            val linePath = Path()
            points.forEachIndexed { i, p ->
                val x = xOf(i)
                val y = yOf(p.balance)
                if (i == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
            }
            drawPath(
                path = linePath,
                color = lineColor,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            // 面积渐变填充
            val areaPath = Path()
            points.forEachIndexed { i, p ->
                val x = xOf(i)
                val y = yOf(p.balance)
                if (i == 0) areaPath.moveTo(x, y) else areaPath.lineTo(x, y)
            }
            val baselineY = top + chartH
            areaPath.lineTo(xOf(points.size - 1), baselineY)
            areaPath.lineTo(xOf(0), baselineY)
            areaPath.close()
            drawPath(
                path = areaPath,
                brush = Brush.verticalGradient(
                    colors = listOf(lineColor.copy(alpha = 0.25f), lineColor.copy(alpha = 0f)),
                    startY = top,
                    endY = baselineY
                )
            )
        }
    }
}

@Composable
private fun TrendSummaryRow(history: List<BalanceSnapshot>) {
    val sorted = history.sortedBy { it.timestamp }
    val latest = sorted.last().balance
    val min = sorted.minOf { it.balance }
    val max = sorted.maxOf { it.balance }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = stringResource(R.string.trend_latest, fmt(latest)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.trend_min, fmt(min)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.trend_max, fmt(max)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun fmt(v: Double): String = String.format(Locale.US, "%.2f", v)
