package com.example.watchdog.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import com.example.watchdog.ui.components.PlatformLogo
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.watchdog.data.model.PlatformType
import com.example.watchdog.ui.components.ApiKeyDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("API Key 管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(uiState.platforms) { platformState ->
                PlatformSettingsCard(
                    platformState = platformState,
                    onToggleEnabled = { enabled ->
                        viewModel.toggleEnabled(platformState.platform, enabled)
                    },
                    onConfigureApiKey = {
                        viewModel.showApiKeyDialog(platformState.platform)
                    }
                )
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                RefreshIntervalCard(
                    currentInterval = uiState.autoRefreshInterval,
                    onIntervalChange = { viewModel.updateRefreshInterval(it) }
                )
            }
        }

        uiState.showApiKeyDialog?.let { platform ->
            val currentApiKey = uiState.platforms
                .find { it.platform == platform }?.apiKey ?: ""

            ApiKeyDialog(
                platform = platform,
                currentApiKey = currentApiKey,
                onDismiss = { viewModel.dismissApiKeyDialog() },
                onSave = { p, key -> viewModel.saveApiKey(p, key) },
                onDelete = { p -> viewModel.deleteApiKey(p) }
            )
        }
    }
}

@Composable
private fun PlatformSettingsCard(
    platformState: PlatformSettingsState,
    onToggleEnabled: (Boolean) -> Unit,
    onConfigureApiKey: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlatformLogo(platform = platformState.platform, size = 28)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = platformState.platform.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (platformState.apiKey.isNotEmpty())
                            "API Key: ${platformState.apiKey.take(6)}...${platformState.apiKey.takeLast(4)}"
                        else
                            "未配置API Key",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = platformState.isEnabled,
                    onCheckedChange = onToggleEnabled,
                    enabled = platformState.apiKey.isNotEmpty()
                )
            }
            if (platformState.apiKey.isEmpty() || !platformState.isEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = onConfigureApiKey,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("配置API Key")
                }
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onConfigureApiKey) {
                        Text("修改")
                    }
                }
            }
        }
    }
}

@Composable
private fun RefreshIntervalCard(
    currentInterval: Int,
    onIntervalChange: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val intervals = listOf(1, 5, 10, 15, 30)

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "自动刷新间隔",
                style = MaterialTheme.typography.titleSmall
            )
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { expanded = true }) {
                    Text("${currentInterval}分钟")
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    intervals.forEach { interval ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (interval == currentInterval) "● ${interval}分钟"
                                    else "${interval}分钟"
                                )
                            },
                            onClick = {
                                onIntervalChange(interval)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}
