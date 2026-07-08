package com.example.watchdog.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.watchdog.WatchDogApplication
import com.example.watchdog.data.model.PlatformType
import com.example.watchdog.data.model.QuotaState
import com.example.watchdog.data.repository.QuotaRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DashboardViewModel(
    private val quotaRepository: QuotaRepository
) : ViewModel() {

    private val _quotaState = MutableStateFlow<QuotaState>(QuotaState.Loading)
    val quotaState: StateFlow<QuotaState> = _quotaState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private var autoRefreshJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            _quotaState.value = QuotaState.Loading

            try {
                val quotas = quotaRepository.fetchAllQuotas()
                val configuredQuotas = quotas.filter { it.isConfigured }
                val failedPlatforms = configuredQuotas
                    .filter { it.errorMessage != null }
                    .map { it.platform }

                _quotaState.value = if (failedPlatforms.isNotEmpty() &&
                    failedPlatforms.size < PlatformType.entries.size
                ) {
                    QuotaState.PartialSuccess(quotas, failedPlatforms)
                } else if (failedPlatforms.size == PlatformType.entries.size) {
                    QuotaState.Error("所有已配置平台查询失败")
                } else {
                    QuotaState.Success(quotas)
                }
            } catch (e: Exception) {
                _quotaState.value = QuotaState.Error(
                    e.localizedMessage ?: "网络请求失败，请检查网络连接"
                )
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun startAutoRefresh(intervalMinutes: Int = 5) {
        autoRefreshJob?.cancel()
        autoRefreshJob = viewModelScope.launch {
            while (true) {
                delay(intervalMinutes * 60 * 1000L)
                refresh()
            }
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
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = WatchDogApplication.instance
                return DashboardViewModel(app.appContainer.quotaRepository) as T
            }
        }
    }
}
