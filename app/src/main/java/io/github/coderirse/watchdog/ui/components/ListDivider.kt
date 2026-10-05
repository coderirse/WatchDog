package io.github.coderirse.watchdog.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 列表内分隔线（设置页 / 更多页共用）。
 *
 * 此前两处实现不一致：MoreScreen 的分割线缩进 52dp 对齐文字起始位置，
 * SettingsScreen 的分割线却是全宽，同一 App 内两套规矩。
 */
@Composable
fun ListDivider(
    leadingInset: Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    HorizontalDivider(
        modifier = modifier.padding(start = leadingInset),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

/** 图标 + 间距的标准缩进（16dp 内容边距 + 22dp 图标 + 14dp 间隔）。 */
val ListDividerIconInset: Dp = 52.dp
