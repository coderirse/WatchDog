package io.github.coderirse.watchdog.ui.dashboard

import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.coderirse.watchdog.R
import io.github.coderirse.watchdog.di.LocalAppContainer
import io.github.coderirse.watchdog.data.model.BalanceSnapshot
import io.github.coderirse.watchdog.data.model.DailyModelUsage
import io.github.coderirse.watchdog.data.model.PlatformType
import io.github.coderirse.watchdog.data.model.QuotaInfo
import io.github.coderirse.watchdog.data.model.QuotaState
import io.github.coderirse.watchdog.data.model.sumCnyBalance
import io.github.coderirse.watchdog.ui.components.DailyUsageCard
import io.github.coderirse.watchdog.ui.components.PlatformQuotaCard
import io.github.coderirse.watchdog.ui.theme.WatchDogTheme
import io.github.coderirse.watchdog.ui.theme.balanceNumeral
import io.github.coderirse.watchdog.ui.theme.onBrand
import io.github.coderirse.watchdog.ui.theme.onBrandSecondary
import io.github.coderirse.watchdog.ui.weblogin.WebLoginActivity
import androidx.compose.ui.platform.LocalContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateToSettings: () -> Unit
) {
    val appContainer = LocalAppContainer.current
    val viewModel: DashboardViewModel = viewModel(factory = DashboardViewModel.factory(appContainer))
    val quotaState by viewModel.quotaState.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val isOffline by viewModel.isOffline.collectAsState()
    val autoRefreshInterval by viewModel.autoRefreshInterval.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    // 从内嵌登录页返回（会话已保存）后立即刷新数据
    val reloginLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) viewModel.refresh()
    }

    // 仅当仪表盘可见且应用处于前台时自动刷新：离开页面或退到后台即停止；
    // 仪表盘重新可见（导航返回/网页登录后跳回/回到前台）立即刷新一次
    DisposableEffect(lifecycleOwner) {
        viewModel.refresh()
        viewModel.loadAutoRefreshInterval()
        viewModel.startAutoRefresh()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    viewModel.refresh()
                    viewModel.startAutoRefresh()
                }
                Lifecycle.Event.ON_PAUSE -> viewModel.stopAutoRefresh()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopAutoRefresh()
        }
    }

    DashboardContent(
        quotaState = quotaState,
        isRefreshing = isRefreshing,
        isOffline = isOffline,
        autoRefreshInterval = autoRefreshInterval,
        onRefresh = { viewModel.refresh() },
        onNavigateToSettings = onNavigateToSettings,
        onRelogin = { platform ->
            reloginLauncher.launch(WebLoginActivity.intent(context, platform))
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardContent(
    quotaState: QuotaState,
    isRefreshing: Boolean,
    isOffline: Boolean,
    autoRefreshInterval: Int,
    onRefresh: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onRelogin: (PlatformType) -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineSmall
                    )
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.topbar_settings_content_desc)
                        )
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.action_refresh)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (val state = quotaState) {
                is QuotaState.Loading -> DashboardSkeleton()
                is QuotaState.Success -> QuotaListOrEmpty(
                    quotas = state.quotas,
                    autoRefreshInterval = autoRefreshInterval,
                    showOfflineBanner = isOffline,
                    dailyModelUsage = state.quotas.flatMap { it.dailyModelUsage },
                    onNavigateToSettings = onNavigateToSettings,
                    onRelogin = onRelogin
                )
                is QuotaState.PartialSuccess -> QuotaListOrEmpty(
                    quotas = state.quotas,
                    autoRefreshInterval = autoRefreshInterval,
                    showOfflineBanner = isOffline,
                    dailyModelUsage = state.quotas.flatMap { it.dailyModelUsage },
                    onNavigateToSettings = onNavigateToSettings,
                    onRelogin = onRelogin
                )
                is QuotaState.Error -> ErrorContent(state.message, onRetry = onRefresh)
            }
        }
    }
}

// ===== Hero 总览卡 =====

private val heroBrush: Brush = Brush.linearGradient(
    colors = listOf(Color(0xFF1E3A8A), Color(0xFF06B6D4))
)

private fun formatClockTime(t: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))

@Composable
fun HeroOverviewCard(quotas: List<QuotaInfo>, modifier: Modifier = Modifier) {
    val configured = quotas.filter { it.isConfigured }
    val abnormalCount = configured.count { it.errorMessage != null }
    // 金额汇总：仅计按量付费且以 CNY 计价的平台；
    // 订阅配额平台无余额概念，GLM 等以 Token 计价的平台不能混入金额求和
    val totalBalance = quotas.sumCnyBalance()
    val hasSubscription = configured.any { it.isSubscriptionMode }
    val lastRefresh = configured.maxOfOrNull { it.lastUpdated }

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth().background(heroBrush)) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    stringResource(R.string.hero_total_balance),
                    style = MaterialTheme.typography.labelMedium,
                    color = onBrandSecondary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = String.format(Locale.US, "%.2f", totalBalance),
                        style = balanceNumeral,
                        color = onBrand
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "CNY",
                        style = MaterialTheme.typography.titleSmall,
                        color = onBrandSecondary,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                if (hasSubscription) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.hero_subscription_excluded),
                        style = MaterialTheme.typography.labelSmall,
                        color = onBrandSecondary
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(
                            R.string.hero_platform_stats,
                            configured.size, quotas.size,
                            if (abnormalCount == 0) stringResource(R.string.hero_status_all_normal)
                            else stringResource(R.string.hero_status_abnormal, abnormalCount)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = onBrandSecondary
                    )
                    if (lastRefresh != null) {
                        Text(
                            stringResource(
                                R.string.hero_last_refresh,
                                formatClockTime(lastRefresh)
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = onBrandSecondary
                        )
                    }
                }
            }
        }
    }
}

// ===== 列表 / 空状态 =====

@Composable
private fun QuotaListOrEmpty(
    quotas: List<QuotaInfo>,
    autoRefreshInterval: Int,
    showOfflineBanner: Boolean,
    dailyModelUsage: List<DailyModelUsage>,
    onNavigateToSettings: () -> Unit,
    onRelogin: (PlatformType) -> Unit = {}
) {
    if (quotas.none { it.isConfigured }) {
        EmptyContent(onNavigateToSettings)
        return
    }
    val configuredQuotas = quotas.filter { it.isConfigured }
    val pendingCount = quotas.size - configuredQuotas.size

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (showOfflineBanner) {
            item(key = "offline_banner") {
                OfflineBanner()
            }
        }

        item(key = "hero_overview") {
            HeroOverviewCard(quotas = quotas)
        }

        item(key = "daily_usage") {
            DailyUsageCard(usages = dailyModelUsage)
        }

        items(configuredQuotas, key = { it.platform.name }) { quota ->
            PlatformQuotaCard(quotaInfo = quota, onRelogin = onRelogin)
        }

        // 未配置平台折叠为单个入口卡（内容完全相同的占位卡不再逐张占据首屏）
        if (pendingCount > 0) {
            item(key = "pending_platforms") {
                PendingPlatformsCard(count = pendingCount, onClick = onNavigateToSettings)
            }
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.dashboard_footer, autoRefreshInterval),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/** 未配置平台折叠入口卡：点击跳转设置页。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PendingPlatformsCard(count: Int, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.dashboard_pending_platforms, count),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun EmptyContent(onNavigateToSettings: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.dashboard_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.dashboard_empty_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onNavigateToSettings) {
                Text(stringResource(R.string.dashboard_empty_action))
            }
        }
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRetry) {
                Text(stringResource(R.string.dashboard_error_retry))
            }
        }
    }
}

// ===== Loading 骨架 =====

@Composable
private fun DashboardSkeleton() {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        userScrollEnabled = false
    ) {
        item { SkeletonBlock(height = 150) }
        items(3) { SkeletonBlock(height = 170) }
    }
}

@Composable
private fun SkeletonBlock(height: Int) {
    // 呼吸式 alpha 动画（shimmer），比纯色块更能传达"加载中"状态
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeleton-alpha"
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
    )
}

@Composable
private fun OfflineBanner() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.dashboard_offline_banner),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

// ===== Preview =====

private val previewQuotas = listOf(
    QuotaInfo(
        platform = PlatformType.DEEPSEEK,
        isAvailable = true,
        isConfigured = true,
        totalBalance = "36.50",
        monthlyUsage = "12.34"
    ),
    QuotaInfo(
        platform = PlatformType.KIMI_CODE,
        isAvailable = true,
        isConfigured = true,
        planName = "Andante 套餐"
    ),
    QuotaInfo.error(PlatformType.GLM, "HTTP 401")
)

@Composable
private fun DashboardPreviewContent() {
    DashboardContent(
        quotaState = QuotaState.Success(previewQuotas),
        isRefreshing = false,
        isOffline = false,
        autoRefreshInterval = 5,
        onRefresh = {},
        onNavigateToSettings = {}
    )
}

@Preview(name = "仪表盘-浅色", showBackground = true)
@Composable
private fun DashboardPreviewLight() {
    WatchDogTheme { DashboardPreviewContent() }
}

@Preview(name = "仪表盘-深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DashboardPreviewDark() {
    WatchDogTheme { DashboardPreviewContent() }
}

@Preview(name = "Hero总览卡-浅色", showBackground = true)
@Composable
private fun HeroOverviewCardPreviewLight() {
    WatchDogTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            HeroOverviewCard(quotas = previewQuotas)
        }
    }
}

@Preview(name = "Hero总览卡-深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HeroOverviewCardPreviewDark() {
    WatchDogTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            HeroOverviewCard(quotas = previewQuotas)
        }
    }
}
