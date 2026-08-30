package io.github.coderirse.watchdog.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.coderirse.watchdog.di.AppContainer
import io.github.coderirse.watchdog.data.local.SettingsStore
import io.github.coderirse.watchdog.data.model.PlatformType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlatformSettingsState(
    val platform: PlatformType,
    val isEnabled: Boolean = false,
    val apiKey: String = "",
    /** 手动填写的初始余额（估算模式平台用，当前仅火山方舟） */
    val initialBalance: Double? = null,
    /** 是否已配置网页控制台会话令牌（爬取数据源；MiMo 必需、DeepSeek 可选；不存明文值） */
    val webSessionConfigured: Boolean = false
)

data class SettingsUiState(
    val platforms: List<PlatformSettingsState> = PlatformType.entries.map {
        PlatformSettingsState(platform = it)
    },
    val autoRefreshInterval: Int = 5,
    val themeMode: String = "system",
    val encryptionDegraded: Boolean = false,
    val balanceAlertEnabled: Boolean = false,
    val balanceAlertThreshold: Double = 10.0,
    val balanceAlertFractionThreshold: Double = 20.0,
    val showApiKeyDialog: PlatformType? = null,
    val showInitialBalanceDialog: Boolean = false,
    val showBalanceThresholdDialog: Boolean = false,
    val showBalanceFractionDialog: Boolean = false
)

class SettingsViewModel(
    private val appContainer: AppContainer
) : ViewModel() {

    private val settingsStore: SettingsStore = appContainer.settingsStore
    private val webSessionStore = appContainer.webSessionStore

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
                    initialBalance = settingsStore.getInitialBalance(platform),
                    webSessionConfigured = if (platform.supportsConsoleSession) {
                        webSessionStore.hasWebSession(platform)
                    } else false
                )
            }
            val interval = settingsStore.getAutoRefreshInterval()
            _uiState.value = _uiState.value.copy(
                platforms = platforms,
                autoRefreshInterval = interval,
                themeMode = appContainer.themeMode.value,
                encryptionDegraded = settingsStore.isEncryptionDegraded(),
                balanceAlertEnabled = settingsStore.isBalanceAlertEnabled(),
                balanceAlertThreshold = settingsStore.getBalanceAlertThreshold(),
                balanceAlertFractionThreshold = settingsStore.getBalanceAlertFraction()
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
            if (platform.supportsConsoleSession) {
                webSessionStore.removeWebSession(platform)
            }
            dismissApiKeyDialog()
            loadSettings()
        }
    }

    /** 保存网页控制台会话令牌（加密存储，界面不回显明文）；会话保存即视为平台启用 */
    fun saveWebSession(platform: PlatformType, token: String) {
        viewModelScope.launch {
            webSessionStore.saveWebSession(platform, token)
            settingsStore.setEnabled(platform, true)
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

    // ===== 余额低水位预警 =====

    fun toggleBalanceAlert(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.saveBalanceAlertEnabled(enabled)
            _uiState.value = _uiState.value.copy(balanceAlertEnabled = enabled)
        }
    }

    fun updateBalanceAlertThreshold(threshold: Double) {
        viewModelScope.launch {
            settingsStore.saveBalanceAlertThreshold(threshold)
            _uiState.value = _uiState.value.copy(balanceAlertThreshold = threshold)
        }
    }

    fun showBalanceThresholdDialog() {
        _uiState.value = _uiState.value.copy(showBalanceThresholdDialog = true)
    }

    fun dismissBalanceThresholdDialog() {
        _uiState.value = _uiState.value.copy(showBalanceThresholdDialog = false)
    }

    fun updateBalanceAlertFraction(percent: Double) {
        viewModelScope.launch {
            settingsStore.saveBalanceAlertFraction(percent)
            _uiState.value = _uiState.value.copy(balanceAlertFractionThreshold = percent)
        }
    }

    fun showBalanceFractionDialog() {
        _uiState.value = _uiState.value.copy(showBalanceFractionDialog = true)
    }

    fun dismissBalanceFractionDialog() {
        _uiState.value = _uiState.value.copy(showBalanceFractionDialog = false)
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(container) }
        }
    }
}
