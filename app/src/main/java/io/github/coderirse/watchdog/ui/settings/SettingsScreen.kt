package io.github.coderirse.watchdog.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.coderirse.watchdog.ui.weblogin.WebLoginActivity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.coderirse.watchdog.R
import io.github.coderirse.watchdog.di.LocalAppContainer
import io.github.coderirse.watchdog.data.model.PlatformType
import io.github.coderirse.watchdog.ui.components.ApiKeyDialog
import io.github.coderirse.watchdog.ui.components.PlatformLogo
import io.github.coderirse.watchdog.ui.theme.WatchDogTheme
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit
) {
    val appContainer = LocalAppContainer.current
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(appContainer))
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // 开启余额预警时，在 Android 13+ 请求通知权限；未授权时通知会静默跳过
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 结果无需处理 */ }

    fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    SettingsContent(
        uiState = uiState,
        onBack = onBack,
        onToggleEnabled = { platform, enabled -> viewModel.toggleEnabled(platform, enabled) },
        onPlatformClick = { viewModel.showApiKeyDialog(it) },
        onIntervalChange = { viewModel.updateRefreshInterval(it) },
        onThemeModeChange = { viewModel.updateThemeMode(it) },
        onEditInitialBalance = { viewModel.showInitialBalanceDialog() },
        onToggleBalanceAlert = { enabled ->
            viewModel.toggleBalanceAlert(enabled)
            if (enabled) requestNotificationPermissionIfNeeded()
        },
        onEditBalanceThreshold = { viewModel.showBalanceThresholdDialog() },
        onEditBalanceFraction = { viewModel.showBalanceFractionDialog() }
    )

    uiState.showApiKeyDialog?.let { platform ->
        val currentApiKey = uiState.platforms
            .find { it.platform == platform }?.apiKey ?: ""
        val sessionConfigured = uiState.platforms
            .find { it.platform == platform }?.webSessionConfigured == true
        val context = LocalContext.current

        ApiKeyDialog(
            platform = platform,
            currentApiKey = currentApiKey,
            sessionConfigured = sessionConfigured,
            onDismiss = { viewModel.dismissApiKeyDialog() },
            onSave = { p, key -> viewModel.saveApiKey(p, key) },
            onSaveWebSession = { p, token -> viewModel.saveWebSession(p, token) },
            onOpenWebLogin = { p ->
                context.startActivity(WebLoginActivity.intent(context, p))
            },
            onDelete = { p -> viewModel.deleteApiKey(p) }
        )
    }

    if (uiState.showInitialBalanceDialog) {
        val currentBalance = uiState.platforms
            .find { it.platform == PlatformType.VOLCENGINE_ARK }?.initialBalance
        InitialBalanceDialog(
            currentBalance = currentBalance,
            onDismiss = { viewModel.dismissInitialBalanceDialog() },
            onSave = { viewModel.saveInitialBalance(it) }
        )
    }

    if (uiState.showBalanceThresholdDialog) {
        BalanceThresholdDialog(
            currentThreshold = uiState.balanceAlertThreshold,
            onDismiss = { viewModel.dismissBalanceThresholdDialog() },
            onSave = { viewModel.updateBalanceAlertThreshold(it) }
        )
    }

    if (uiState.showBalanceFractionDialog) {
        BalanceFractionDialog(
            currentFraction = uiState.balanceAlertFractionThreshold,
            onDismiss = { viewModel.dismissBalanceFractionDialog() },
            onSave = { viewModel.updateBalanceAlertFraction(it) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    onToggleEnabled: (PlatformType, Boolean) -> Unit,
    onPlatformClick: (PlatformType) -> Unit,
    onIntervalChange: (Int) -> Unit,
    onThemeModeChange: (String) -> Unit,
    onEditInitialBalance: () -> Unit,
    onToggleBalanceAlert: (Boolean) -> Unit,
    onEditBalanceThreshold: () -> Unit,
    onEditBalanceFraction: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
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
            if (uiState.encryptionDegraded) {
                item(key = "encryption_warning") {
                    EncryptionWarningCard()
                }
            }

            item {
                SectionHeader(stringResource(R.string.settings_group_platforms))
            }

            items(uiState.platforms, key = { it.platform.name }) { platformState ->
                PlatformSettingsCard(
                    platformState = platformState,
                    onToggleEnabled = { enabled ->
                        onToggleEnabled(platformState.platform, enabled)
                    },
                    onClick = { onPlatformClick(platformState.platform) },
                    onEditInitialBalance = onEditInitialBalance
                )
            }

            item {
                Spacer(modifier = Modifier.height(4.dp))
                SectionHeader(stringResource(R.string.settings_group_general))
            }

            item {
                RefreshIntervalCard(
                    currentInterval = uiState.autoRefreshInterval,
                    onIntervalChange = onIntervalChange
                )
            }

            item {
                ThemeCard(
                    themeMode = uiState.themeMode,
                    onThemeModeChange = onThemeModeChange
                )
            }

            item {
                BalanceAlertCard(
                    enabled = uiState.balanceAlertEnabled,
                    threshold = uiState.balanceAlertThreshold,
                    fractionThreshold = uiState.balanceAlertFractionThreshold,
                    onToggleEnabled = onToggleBalanceAlert,
                    onEditThreshold = onEditBalanceThreshold,
                    onEditFraction = onEditBalanceFraction
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}

// ===== 平台配置卡 =====

@Composable
private fun consoleHost(platform: PlatformType): String = when (platform) {
    PlatformType.DEEPSEEK -> stringResource(R.string.settings_console_deepseek)
    PlatformType.KIMI -> stringResource(R.string.settings_console_kimi)
    PlatformType.GLM -> stringResource(R.string.settings_console_glm)
    PlatformType.SILICONFLOW -> stringResource(R.string.settings_console_siliconflow)
    PlatformType.VOLCENGINE_ARK -> stringResource(R.string.settings_console_volcengine)
    PlatformType.KIMI_CODE -> stringResource(R.string.settings_console_kimi_code)
    PlatformType.MIMO -> stringResource(R.string.settings_console_mimo)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlatformSettingsCard(
    platformState: PlatformSettingsState,
    onToggleEnabled: (Boolean) -> Unit,
    onClick: () -> Unit,
    onEditInitialBalance: () -> Unit
) {
    val platform = platformState.platform
    val hasKey = platformState.apiKey.isNotEmpty()

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlatformLogo(platform = platform, size = 32)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = platform.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (hasKey)
                            stringResource(
                                R.string.settings_api_key_masked,
                                platformState.apiKey.take(6),
                                platformState.apiKey.takeLast(4)
                            )
                        else stringResource(R.string.settings_api_key_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = platformState.isEnabled,
                    onCheckedChange = onToggleEnabled,
                    enabled = hasKey || platformState.webSessionConfigured
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            val host = consoleHost(platform)
            val ctx = LocalContext.current
            Text(
                text = stringResource(R.string.settings_create_key_hint, host),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable {
                    runCatching {
                        ctx.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://$host")
                            )
                        )
                    }
                }
            )

            if (platform == PlatformType.KIMI_CODE) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.settings_kimi_code_independent),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            // 网页控制台会话配置（小米 MiMo 必需；DeepSeek 可选增强）
            if (platform.supportsConsoleSession) {
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_console_session_title),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = if (platformState.webSessionConfigured) {
                                stringResource(R.string.settings_console_session_configured)
                            } else {
                                stringResource(R.string.settings_console_session_empty)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (!platformState.webSessionConfigured && platform == PlatformType.MIMO) {
                                // 仅必需会话的平台未配置时标红（MiMo）；DeepSeek 为可选增强，低调提示
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    TextButton(onClick = onClick) {
                        Text(stringResource(R.string.settings_console_session_edit))
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (platform == PlatformType.MIMO) {
                        stringResource(R.string.settings_mimo_session_desc)
                    } else {
                        stringResource(R.string.settings_deepseek_session_desc)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            if (platform == PlatformType.VOLCENGINE_ARK) {
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_ark_initial_balance_title),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = platformState.initialBalance?.let {
                                stringResource(R.string.settings_ark_initial_balance_value, it.toString())
                            } ?: stringResource(R.string.settings_ark_initial_balance_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = onEditInitialBalance) {
                        Text(stringResource(R.string.settings_ark_edit_balance))
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.settings_ark_initial_balance_desc),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

// ===== 通用设置 =====

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
                text = stringResource(R.string.settings_refresh_interval),
                style = MaterialTheme.typography.titleSmall
            )
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { expanded = true }) {
                    Text(stringResource(R.string.settings_interval_minutes, currentInterval))
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    intervals.forEach { interval ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (interval == currentInterval)
                                        stringResource(R.string.settings_interval_selected, interval)
                                    else stringResource(R.string.settings_interval_minutes, interval)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeCard(
    themeMode: String,
    onThemeModeChange: (String) -> Unit
) {
    val options = listOf(
        "system" to stringResource(R.string.theme_follow_system),
        "light" to stringResource(R.string.theme_light),
        "dark" to stringResource(R.string.theme_dark)
    )

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.settings_theme_title),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(10.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = themeMode == mode,
                        onClick = { onThemeModeChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = options.size
                        )
                    ) {
                        Text(label)
                    }
                }
            }
        }
    }
}

// ===== 加密降级告警卡 =====

@Composable
private fun EncryptionWarningCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.settings_encryption_degraded),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

// ===== 余额预警卡 =====

@Composable
private fun BalanceAlertCard(
    enabled: Boolean,
    threshold: Double,
    fractionThreshold: Double,
    onToggleEnabled: (Boolean) -> Unit,
    onEditThreshold: () -> Unit,
    onEditFraction: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_balance_alert_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = stringResource(R.string.settings_balance_alert_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggleEnabled
                )
            }
            if (enabled) {
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(10.dp))
                ThresholdRow(
                    label = stringResource(R.string.settings_balance_alert_threshold),
                    value = stringResource(
                        R.string.settings_balance_alert_threshold_value,
                        formatThreshold(threshold)
                    ),
                    onEdit = onEditThreshold
                )
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(10.dp))
                ThresholdRow(
                    label = stringResource(R.string.settings_balance_alert_fraction),
                    value = stringResource(
                        R.string.settings_balance_alert_fraction_value,
                        formatThreshold(fractionThreshold)
                    ),
                    onEdit = onEditFraction
                )
            }
        }
    }
}

@Composable
private fun ThresholdRow(label: String, value: String, onEdit: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onEdit) {
            Text(stringResource(R.string.settings_balance_alert_edit))
        }
    }
}

private fun formatThreshold(value: Double): String {
    return if (value == value.toLong().toDouble()) {
        value.toLong().toString()
    } else {
        String.format(Locale.US, "%.2f", value)
    }
}

// ===== 余额预警阈值弹窗 =====

@Composable
private fun BalanceThresholdDialog(
    currentThreshold: Double,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit
) {
    var input by remember { mutableStateOf(formatThreshold(currentThreshold)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.balance_threshold_dialog_title),
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.balance_threshold_dialog_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(stringResource(R.string.balance_threshold_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { input.toDoubleOrNull()?.let(onSave) },
                enabled = input.toDoubleOrNull()?.let { it >= 0.0 } == true
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

// ===== 余额预警比例阈值弹窗 =====

@Composable
private fun BalanceFractionDialog(
    currentFraction: Double,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit
) {
    var input by remember { mutableStateOf(formatThreshold(currentFraction)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.balance_fraction_dialog_title),
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.balance_fraction_dialog_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(stringResource(R.string.balance_fraction_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { input.toDoubleOrNull()?.let(onSave) },
                enabled = input.toDoubleOrNull()?.let { it in 0.0..100.0 } == true
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

// ===== 火山方舟初始余额弹窗 =====

@Composable
private fun InitialBalanceDialog(
    currentBalance: Double?,
    onDismiss: () -> Unit,
    onSave: (Double?) -> Unit
) {
    var input by remember { mutableStateOf(currentBalance?.toString() ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.initial_balance_dialog_title),
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.initial_balance_dialog_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(stringResource(R.string.initial_balance_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { input.toDoubleOrNull()?.let(onSave) },
                enabled = input.toDoubleOrNull() != null
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            Row {
                if (currentBalance != null) {
                    TextButton(onClick = { onSave(null) }) {
                        Text(
                            text = stringResource(R.string.action_clear),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    )
}

// ===== Preview =====

private val previewUiState = SettingsUiState(
    platforms = listOf(
        PlatformSettingsState(PlatformType.DEEPSEEK, isEnabled = true, apiKey = "sk-abcdef1234567890"),
        PlatformSettingsState(PlatformType.KIMI),
        PlatformSettingsState(PlatformType.GLM, isEnabled = true, apiKey = "glm-xyz987654321"),
        PlatformSettingsState(PlatformType.SILICONFLOW),
        PlatformSettingsState(PlatformType.VOLCENGINE_ARK, isEnabled = true, apiKey = "ark-abcd1234", initialBalance = 100.0),
        PlatformSettingsState(PlatformType.KIMI_CODE),
        PlatformSettingsState(PlatformType.MIMO, isEnabled = true, apiKey = "sk-mimo123456", webSessionConfigured = true)
    ),
    autoRefreshInterval = 5,
    themeMode = "system",
    encryptionDegraded = true,
    balanceAlertEnabled = true,
    balanceAlertThreshold = 10.0,
    balanceAlertFractionThreshold = 20.0
)

@Composable
private fun SettingsPreviewContent() {
    SettingsContent(
        uiState = previewUiState,
        onBack = {},
        onToggleEnabled = { _, _ -> },
        onPlatformClick = {},
        onIntervalChange = {},
        onThemeModeChange = {},
        onEditInitialBalance = {},
        onToggleBalanceAlert = {},
        onEditBalanceThreshold = {},
        onEditBalanceFraction = {}
    )
}

@Preview(name = "设置-浅色", showBackground = true)
@Composable
private fun SettingsPreviewLight() {
    WatchDogTheme { SettingsPreviewContent() }
}

@Preview(name = "设置-深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SettingsPreviewDark() {
    WatchDogTheme { SettingsPreviewContent() }
}
