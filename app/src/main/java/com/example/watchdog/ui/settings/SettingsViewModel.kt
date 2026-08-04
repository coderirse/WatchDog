package com.example.watchdog.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.watchdog.di.AppContainer
import com.example.watchdog.data.local.SettingsStore
import com.example.watchdog.data.model.PlatformType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlatformSettingsState(
    val platform: PlatformType,
    val isEnabled: Boolean = false,
    val apiKey: String = "",
    /** 手动填写的初始余额（估算模式平台用，当前仅火山方舟） */
    val initialBalance: Double? = null
)

data class SettingsUiState(
    val platforms: List<PlatformSettingsState> = PlatformType.entries.map {
        PlatformSettingsState(platform = it)
    },
    val autoRefreshInterval: Int = 5,
    val themeMode: String = "system",
    val showApiKeyDialog: PlatformType? = null,
    val showInitialBalanceDialog: Boolean = false
)

class SettingsViewModel(
    private val appContainer: AppContainer
) : ViewModel() {

    private val settingsStore: SettingsStore = appContainer.settingsStore

    private val _uiState = MutableStateFlow(SettingsUiState(themeMode = appContainer.themeMode.value))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    fun loadSettings() {
        viewModelScope.launch {
            val platforms = PlatformType.entries.map { platform ->
                val apiKey = settingsStore.getApiKey(platform) ?: ""
                val enabled = settingsStore.isEnabled(platform)
                PlatformSettingsState(
                    platform = platform,
                    isEnabled = enabled,
                    apiKey = apiKey,
                    initialBalance = settingsStore.getInitialBalance(platform)
                )
            }
            val interval = settingsStore.getAutoRefreshInterval()
            _uiState.value = _uiState.value.copy(
                platforms = platforms,
                autoRefreshInterval = interval,
                themeMode = appContainer.themeMode.value
            )
        }
    }

    fun toggleEnabled(platform: PlatformType, enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setEnabled(platform, enabled)
            loadSettings()
        }
    }

    fun showApiKeyDialog(platform: PlatformType) {
        _uiState.value = _uiState.value.copy(showApiKeyDialog = platform)
    }

    fun dismissApiKeyDialog() {
        _uiState.value = _uiState.value.copy(showApiKeyDialog = null)
    }

    fun saveApiKey(platform: PlatformType, apiKey: String) {
        viewModelScope.launch {
            settingsStore.saveApiKey(platform, apiKey)
            settingsStore.setEnabled(platform, true)
            dismissApiKeyDialog()
            loadSettings()
        }
    }

    fun deleteApiKey(platform: PlatformType) {
        viewModelScope.launch {
            settingsStore.removeApiKey(platform)
            settingsStore.setEnabled(platform, false)
            dismissApiKeyDialog()
            loadSettings()
        }
    }

    fun updateRefreshInterval(minutes: Int) {
        viewModelScope.launch {
            settingsStore.saveAutoRefreshInterval(minutes)
            _uiState.value = _uiState.value.copy(autoRefreshInterval = minutes)
        }
    }

    /** 修改主题模式：持久化并通过 AppContainer 状态即时应用到 MainActivity */
    fun updateThemeMode(mode: String) {
        viewModelScope.launch {
            appContainer.setThemeMode(mode)
            _uiState.value = _uiState.value.copy(themeMode = mode)
        }
    }

    fun showInitialBalanceDialog() {
        _uiState.value = _uiState.value.copy(showInitialBalanceDialog = true)
    }

    fun dismissInitialBalanceDialog() {
        _uiState.value = _uiState.value.copy(showInitialBalanceDialog = false)
    }

    /** 保存火山方舟手动初始余额；传 null 表示清除 */
    fun saveInitialBalance(balance: Double?) {
        viewModelScope.launch {
            settingsStore.saveInitialBalance(PlatformType.VOLCENGINE_ARK, balance)
            dismissInitialBalanceDialog()
            loadSettings()
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SettingsViewModel(container) as T
            }
        }
    }
}
