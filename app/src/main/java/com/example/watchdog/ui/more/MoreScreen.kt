package com.example.watchdog.ui.more

import android.app.Application
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.watchdog.R
import com.example.watchdog.ui.theme.WatchDogTheme

private const val REPO_URL = "https://github.com/coderirse/WatchDog"
private const val RELEASES_URL = "https://github.com/coderirse/WatchDog/releases/latest"
private const val LICENSE_URL = "https://github.com/coderirse/WatchDog/blob/master/LICENSE"

private val openSourceLibs = listOf(
    "Jetpack Compose" to "Apache 2.0",
    "Retrofit 2" to "Apache 2.0",
    "OkHttp" to "Apache 2.0",
    "Coil" to "Apache 2.0",
    "Material Icons" to "Apache 2.0",
    "Gson" to "Apache 2.0",
    "LobeHub AI Icons" to "CC BY-SA 4.0"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen() {
    val context = LocalContext.current
    val viewModelFactory = remember { MoreViewModel.factory(context.applicationContext as Application) }
    val viewModel: MoreViewModel = viewModel(factory = viewModelFactory)
    val state by viewModel.uiState.collectAsState()

    MoreContent(
        state = state,
        onCheckUpdate = { viewModel.checkForUpdate() }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreContent(
    state: MoreUiState,
    onCheckUpdate: () -> Unit
) {
    val context = LocalContext.current
    var libsExpanded by remember { mutableStateOf(false) }

    fun openUrl(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.more_title)) })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ===== 支持 =====
            GroupHeader(stringResource(R.string.more_group_support))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    MoreRow(
                        icon = Icons.Filled.Refresh,
                        title = stringResource(R.string.more_check_update),
                        subtitle = stringResource(R.string.more_current_version, state.currentVersion),
                        onClick = { if (!state.isChecking) onCheckUpdate() },
                        trailing = {
                            if (state.isChecking) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Text(
                                    text = stringResource(R.string.more_check_action),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    )
                    if (state.checkResult.isNotEmpty()) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                            Text(
                                text = state.checkResult,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (state.hasUpdate) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (state.hasUpdate && state.latestVersion != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.more_download_update, state.latestVersion ?: ""),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable { openUrl(RELEASES_URL) }
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                    RowDivider()
                    MoreRow(
                        icon = Icons.Filled.Star,
                        title = stringResource(R.string.more_github_repo),
                        subtitle = stringResource(R.string.more_github_subtitle),
                        onClick = { openUrl(REPO_URL) },
                        trailing = { TrailingArrow() }
                    )
                }
            }

            // ===== 关于 =====
            GroupHeader(stringResource(R.string.more_group_about))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    MoreRow(
                        icon = Icons.Filled.Info,
                        title = stringResource(R.string.more_license),
                        subtitle = stringResource(R.string.more_license_subtitle),
                        onClick = { openUrl(LICENSE_URL) },
                        trailing = { TrailingArrow() }
                    )
                    RowDivider()
                    MoreRow(
                        icon = Icons.Filled.Build,
                        title = stringResource(R.string.more_open_libs),
                        subtitle = stringResource(R.string.more_open_libs_subtitle, openSourceLibs.size),
                        onClick = { libsExpanded = !libsExpanded },
                        trailing = { TrailingArrow() }
                    )
                    if (libsExpanded) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Spacer(modifier = Modifier.height(8.dp))
                            openSourceLibs.forEach { (name, license) ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(name, style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        license,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                    RowDivider()
                    MoreRow(
                        icon = Icons.Filled.Check,
                        title = stringResource(R.string.more_version_info),
                        subtitle = null,
                        onClick = null,
                        trailing = {
                            Text(
                                text = "v${state.currentVersion}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )
                }
            }

            // 版权
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.more_copyright),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)
            )
        }
    }
}

@Composable
private fun GroupHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 52.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

@Composable
private fun TrailingArrow() {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.outline,
        modifier = Modifier.size(20.dp)
    )
}

@Composable
private fun MoreRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: (() -> Unit)?,
    trailing: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        trailing()
    }
}

// ===== Preview =====

@Composable
private fun MorePreviewContent() {
    MoreContent(
        state = MoreUiState(currentVersion = "1.0.6", checkResult = "已是最新版本 v1.0.6"),
        onCheckUpdate = {}
    )
}

@Preview(name = "更多-浅色", showBackground = true)
@Composable
private fun MorePreviewLight() {
    WatchDogTheme { MorePreviewContent() }
}

@Preview(name = "更多-深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MorePreviewDark() {
    WatchDogTheme { MorePreviewContent() }
}
