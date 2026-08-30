package com.example.watchdog.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.watchdog.R
import com.example.watchdog.data.model.PlatformType

@Composable
fun ApiKeyDialog(
    platform: PlatformType,
    currentApiKey: String = "",
    sessionConfigured: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (platform: PlatformType, apiKey: String) -> Unit,
    onSaveWebSession: (platform: PlatformType, token: String) -> Unit,
    onOpenWebLogin: (platform: PlatformType) -> Unit = {},
    onDelete: (platform: PlatformType) -> Unit
) {
    var apiKeyInput by remember { mutableStateOf(currentApiKey) }
    var sessionInput by remember { mutableStateOf("") }

    val canSave = apiKeyInput.isNotBlank() ||
        (platform.supportsConsoleSession && sessionInput.isNotBlank())

    fun submit() {
        if (!canSave) return
        if (apiKeyInput.isNotBlank()) {
            onSave(platform, apiKeyInput.trim())
        }
        // 会话可独立于 API Key 保存（MiMo 等无官方余额 API 的平台仅需会话）
        if (platform.supportsConsoleSession && sessionInput.isNotBlank()) {
            onSaveWebSession(platform, sessionInput.trim())
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            PlatformLogo(platform = platform, size = 40)
        },
        title = {
            Text(
                text = stringResource(R.string.apikey_dialog_title, platform.displayName),
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.apikey_dialog_desc, platform.displayName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { apiKeyInput = it },
                    label = { Text("API Key") },
                    placeholder = { Text(stringResource(R.string.apikey_dialog_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = if (platform.supportsConsoleSession) ImeAction.Next else ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { submit() }
                    )
                )
                if (currentApiKey.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.apikey_dialog_current),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                // 支持网页控制台会话的平台（小米 MiMo 必需 / DeepSeek 可选增强）：
                // 推荐通过内嵌登录页获取会话（支持密码/短信验证码登录，凭证自动抓取加密保存）；
                // 手动粘贴令牌作为备选。
                if (platform.supportsConsoleSession) {
                    val sessionDesc = when (platform) {
                        PlatformType.MIMO ->
                            stringResource(R.string.apikey_dialog_session_desc_mimo, platform.displayName)
                        PlatformType.DEEPSEEK ->
                            stringResource(R.string.apikey_dialog_session_desc_deepseek, platform.displayName)
                        else -> ""
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = sessionDesc,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { onOpenWebLogin(platform) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.apikey_dialog_open_weblogin, platform.displayName))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = sessionInput,
                        onValueChange = { sessionInput = it },
                        label = { Text(stringResource(R.string.apikey_dialog_session_label)) },
                        placeholder = { Text(stringResource(R.string.apikey_dialog_session_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { submit() })
                    )
                    if (sessionConfigured) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.apikey_dialog_session_current),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { submit() },
                enabled = canSave
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            Row {
                if (currentApiKey.isNotEmpty()) {
                    TextButton(onClick = { onDelete(platform) }) {
                        Text(
                            text = stringResource(R.string.action_delete),
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
