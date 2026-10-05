package io.github.coderirse.watchdog.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.coderirse.watchdog.data.local.BalanceAlertManager
import io.github.coderirse.watchdog.data.local.BalanceHistoryStore
import io.github.coderirse.watchdog.data.model.BalanceSnapshot
import io.github.coderirse.watchdog.data.model.PlatformType
import io.github.coderirse.watchdog.data.model.QuotaInfo
import io.github.coderirse.watchdog.data.model.QuotaState
import io.github.coderirse.watchdog.data.model.sumCnyBalance
import io.github.coderirse.watchdog.data.repository.QuotaRepository
import io.github.coderirse.watchdog.data.repository.RefreshIntervalSource
import io.github.coderirse.watchdog.di.AppContainer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 仪表盘状态与刷新编排。
 *
 * 依赖收集收敛为接口（[QuotaRepository] 与下表中的窄接口），使本类可以用纯 JVM
 * 单测覆盖"三态推导 / 缓存回退判定 / 趋势记录条件 / 并发刷新去重"等关键分支，
 * 无需引入 Robolectric（与 QuotaRepository 的测试策略保持一致）。
 */
class DashboardViewModel(
    private val quotaRepository: QuotaRepository,
    private val refreshIntervalSource: RefreshIntervalSource,
    private val alertEvaluator: suspend (List<QuotaInfo>) -> Unit,
    private val historyRecorder: suspend (Double) -> Unit,
    private val historyLoader: suspend () -> List<BalanceSnapshot>
) : ViewModel() {

    private val _quotaState = MutableStateFlow<QuotaState>(QuotaState.Loading)
    val quotaState: StateFlow<QuotaState> = _quotaState.asStateFlow()

    private val _balanceHistory = MutableStateFlow<List<BalanceSnapshot>>(emptyList())
    val balanceHistory: StateFlow<List<BalanceSnapshot>> = _balanceHistory.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _isOffline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> = _isOffline.asStateFlow()

    private val _autoRefreshInterval = MutableStateFlow(5)
    val autoRefreshInterval: StateFlow<Int> = _autoRefreshInterval.asStateFlow()

    private var autoRefreshJob: Job? = null
    private var refreshJob: Job? = null

    init {
        refresh()
        loadHistory()
    }

    fun refresh() {
        // 已有请求在进行中时跳过，避免手动/下拉/自动刷新并发重复打接口
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _isRefreshing.value = true
            // 已有数据时静默刷新，避免整页内容被 Loading 替换闪烁
            val current = _quotaState.value
            if (current !is QuotaState.Success && current !is QuotaState.PartialSuccess) {
                _quotaState.value = QuotaState.Loading
            }

            try {
                val quotas = quotaRepository.fetchAllQuotas()
                // 刷新后评估余额低水位，对"新进入"预警状态的平台发送本地通知；
                // 通知评估/发送失败不应影响仪表盘展示，故单独隔离
                try {
                    alertEvaluator(quotas)
                } catch (_: Exception) {
                    // 忽略通知异常
                }
                // 记录余额历史快照：仅当本次 CNY 平台全部成功获取时记录，
                // 避免断网/部分失败导致趋势图出现假性波动
                try {
                    recordHistoryIfFresh(quotas)
                } catch (_: Exception) {
                    // 忽略历史记录异常
                }
                val configuredQuotas = quotas.filter { it.isConfigured }
                val failedPlatforms = configuredQuotas
                    .filter { it.errorMessage != null }
                    .map { it.platform }

                _isOffline.value = quotas.any { it.isStale }
                _quotaState.value = when {
                    failedPlatforms.isNotEmpty() && failedPlatforms.size < configuredQuotas.size ->
                        QuotaState.PartialSuccess(quotas, failedPlatforms)
                    configuredQuotas.isNotEmpty() && failedPlatforms.size == configuredQuotas.size ->
                        // Error.message 只承载 locale 中立的诊断明细（平台名：原因），
                        // 面向用户的文案由 UI 层组装（避免 VM 硬编码中文、不可本地化）
                        QuotaState.Error(
                            configuredQuotas.joinToString("；") {
                                "${it.platform.displayName}：${it.errorMessage ?: "—"}"
                            }
                        )
                    else -> QuotaState.Success(quotas)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // 协程取消向上传播
            } catch (e: Exception) {
                _isOffline.value = false
                _quotaState.value = QuotaState.Error(e.localizedMessage ?: "")
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /**
     * 记录总余额快照（条件见下方注释），并同步刷新趋势图数据源。
     * 内部可见以便单测直接覆盖判定条件。
     */
    internal suspend fun recordHistoryIfFresh(quotas: List<QuotaInfo>) {
        val cnyQuotas = quotas.filter { it.isConfigured && it.currency == "CNY" }
        if (cnyQuotas.isNotEmpty() && cnyQuotas.all { it.errorMessage == null && !it.isStale }) {
            historyRecorder(quotas.sumCnyBalance(freshOnly = true))
            _balanceHistory.value = historyLoader()
        }
    }

    fun startAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = viewModelScope.launch {
            while (true) {
                val intervalMinutes = refreshIntervalSource.getAutoRefreshInterval().coerceAtLeast(1)
                _autoRefreshInterval.value = intervalMinutes
                delay(intervalMinutes * 60 * 1000L)
                refresh()
            }
        }
    }

    fun loadAutoRefreshInterval() {
        viewModelScope.launch {
            _autoRefreshInterval.value =
                refreshIntervalSource.getAutoRefreshInterval().coerceAtLeast(1)
        }
    }

    fun loadHistory() {
        viewModelScope.launch {
            _balanceHistory.value = historyLoader()
        }
    }

    fun stopAutoRefresh() {
        autoRefreshJob?.cancel()
    }

    override fun onCleared() {
        super.onCleared()
        stopAutoRefresh()
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                DashboardViewModel(
                    quotaRepository = container.quotaRepository,
                    refreshIntervalSource = container.settingsStore,
                    alertEvaluator = { quotas -> container.balanceAlertManager.evaluate(quotas) },
                    historyRecorder = { balance -> container.balanceHistoryStore.record(balance) },
                    historyLoader = { container.balanceHistoryStore.load() }
                )
            }
        }
    }
}
