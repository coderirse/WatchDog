package io.github.coderirse.watchdog.ui.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import io.github.coderirse.watchdog.ui.theme.balanceNumeral

/**
 * 余额大数字：固定单行 + 自动缩字号。
 *
 * 背景：`balanceNumeral` 固定 40sp，余额位数多时（如 `1234567.89`）
 * 会溢出或挤压同行货币单位；`BasicText` 的 `TextAutoSize.StepBased`
 * 让文字在 [minFontSize, maxFontSize] 区间内按可用宽度自适应缩放。
 * 保留 `tnum`（tabular 数字），金额刷新时不跳动。
 */
@Composable
fun BalanceText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = balanceNumeral,
    minFontSize: TextUnit = 24.sp,
    maxFontSize: TextUnit = 40.sp
) {
    BasicText(
        text = text,
        style = style,
        color = { color },
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(
            minFontSize = minFontSize,
            maxFontSize = maxFontSize,
            stepSize = 1.sp
        ),
        modifier = modifier
    )
}
