package com.example.watchdog.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.watchdog.di.AppContainer
import com.example.watchdog.data.local.BalanceAlertManager
import com.example.watchdog.data.local.BalanceHistoryStore
import com.example.watchdog.data.local.SettingsStore
import com.example.watchdog.data.model.BalanceSnapshot
import com.example.watchdog.data.model.PlatformType
import com.example.watchdog.data.model.QuotaState
import com.example.watchdog.data.model.sumCnyBalance
import com.example.watchdog.data.repository.QuotaRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DashboardViewModel(
    private val quotaRepository: QuotaRepository,
    private val settingsStore: SettingsStore,
    private val balanceAlertManager: BalanceAlertManager,
    private val balanceHistoryStore: BalanceHistoryStore
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
                    balanceAlertManager.evaluate(quotas)
                } catch (_: Exception) {
                    // 忽略通知异常
                }
                // 记录余额历史快照：仅当本次 CNY 平台全部成功获取时记录，
                // 避免断网/部分失败导致趋势图出现假性波动
                try {
                    val cnyQuotas = quotas.filter { it.isConfigured && it.currency == "CNY" }
                    if (cnyQuotas.isNotEmpty() &&
                        cnyQuotas.all { it.errorMessage == null && !it.isStale }
                    ) {
                        balanceHistoryStore.record(quotas.sumCnyBalance(freshOnly = true))
                        _balanceHistory.value = balanceHistoryStore.load()
                    }
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
                        QuotaState.Error(
                            "所有已配置平台查询失败：" + configuredQuotas.joinToString("；") {
                                "${it.platform.displayName}：${it.errorMessage ?: "未知错误"}"
                            }
                        )
                    else -> QuotaState.Success(quotas)
                }
            } catch (e: Exception) {
                _isOffline.value = false
                _quotaState.value = QuotaState.Error(
                    e.localizedMessage ?: "网络请求失败，请检查网络连接"
                )
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun startAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = viewModelScope.launch {
            while (true) {
                val intervalMinutes = settingsStore.getAutoRefreshInterval().coerceAtLeast(1)
                _autoRefreshInterval.value = intervalMinutes
                delay(intervalMinutes * 60 * 1000L)
                refresh()
            }
        }
    }

    fun loadAutoRefreshInterval() {
        viewModelScope.launch {
            _autoRefreshInterval.value = settingsStore.getAutoRefreshInterval().coerceAtLeast(1)
        }
    }

    fun loadHistory() {
        viewModelScope.launch {
            _balanceHistory.value = balanceHistoryStore.load()
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
        fun factory(container: AppContainer): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return DashboardViewModel(
                    quotaRepository = container.quotaRepository,
                    settingsStore = container.settingsStore,
                    balanceAlertManager = container.balanceAlertManager,
                    balanceHistoryStore = container.balanceHistoryStore
                ) as T
            }
        }
    }
}
