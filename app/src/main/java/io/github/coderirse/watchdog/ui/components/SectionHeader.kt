package io.github.coderirse.watchdog.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 分组小标题（"支持"/"关于"/"平台配置"等）。
 *
 * 此前 MoreScreen.GroupHeader 与 SettingsScreen.SectionHeader 逐字重复，
 * 统一到本组件，保证两页分组标题视觉一致。
 */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = modifier.padding(start = 4.dp, top = 4.dp)
    )
}
