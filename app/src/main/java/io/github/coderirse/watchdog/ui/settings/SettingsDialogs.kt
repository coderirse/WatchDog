package io.github.coderirse.watchdog.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.coderirse.watchdog.R
import io.github.coderirse.watchdog.ui.components.AnimatedDialog
import java.util.Locale

// ===== 余额预警阈值弹窗 =====

@Composable
internal fun BalanceThresholdDialog(
    currentThreshold: Double,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit
) {
    var input by remember { mutableStateOf(formatThreshold(currentThreshold)) }

    AnimatedDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.balance_threshold_dialog_title),
        buttons = { closer ->
            TextButton(onClick = { closer.dismiss() }) {
                Text(stringResource(R.string.action_cancel))
            }
            TextButton(
                onClick = { closer.dismiss { input.toDoubleOrNull()?.let(onSave) } },
                enabled = input.toDoubleOrNull()?.let { it >= 0.0 } == true
            ) {
                Text(stringResource(R.string.action_save))
            }
        }
    ) {
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
}

// ===== 余额预警比例阈值弹窗 =====

@Composable
internal fun BalanceFractionDialog(
    currentFraction: Double,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit
) {
    var input by remember { mutableStateOf(formatThreshold(currentFraction)) }

    AnimatedDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.balance_fraction_dialog_title),
        buttons = { closer ->
            TextButton(onClick = { closer.dismiss() }) {
                Text(stringResource(R.string.action_cancel))
            }
            TextButton(
                onClick = { closer.dismiss { input.toDoubleOrNull()?.let(onSave) } },
                enabled = input.toDoubleOrNull()?.let { it in 0.0..100.0 } == true
            ) {
                Text(stringResource(R.string.action_save))
            }
        }
    ) {
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
}

// ===== 火山方舟初始余额弹窗 =====

@Composable
internal fun InitialBalanceDialog(
    currentBalance: Double?,
    onDismiss: () -> Unit,
    onSave: (Double?) -> Unit
) {
    var input by remember { mutableStateOf(currentBalance?.toString() ?: "") }

    AnimatedDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.initial_balance_dialog_title),
        buttons = { closer ->
            if (currentBalance != null) {
                TextButton(onClick = { closer.dismiss { onSave(null) } }) {
                    Text(
                        text = stringResource(R.string.action_clear),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            TextButton(onClick = { closer.dismiss() }) {
                Text(stringResource(R.string.action_cancel))
            }
            TextButton(
                onClick = { closer.dismiss { input.toDoubleOrNull()?.let(onSave) } },
                enabled = input.toDoubleOrNull() != null
            ) {
                Text(stringResource(R.string.action_save))
            }
        }
    ) {
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
}

/** 阈值格式化：整数不带小数位，小数保留两位（设置卡与弹窗共用）。 */
internal fun formatThreshold(value: Double): String {
    return if (value == value.toLong().toDouble()) {
        value.toLong().toString()
    } else {
        String.format(Locale.US, "%.2f", value)
    }
}
