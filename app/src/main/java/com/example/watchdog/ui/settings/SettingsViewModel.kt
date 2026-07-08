package com.example.watchdog.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.watchdog.WatchDogApplication
import com.example.watchdog.data.local.SettingsStore
import com.example.watchdog.data.model.PlatformType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlatformSettingsState(
    val platform: PlatformType,
    val isEnabled: Boolean = false,
    val apiKey: String = ""
)

data class SettingsUiState(
    val platforms: List<PlatformSettingsState> = PlatformType.entries.map {
        PlatformSettingsState(platform = it)
    },
    val autoRefreshInterval: Int = 5,
    val showApiKeyDialog: PlatformType? = null
)

class SettingsViewModel(
    private val settingsStore: SettingsStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
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
                    apiKey = apiKey
                )
            }
            val interval = settingsStore.getAutoRefreshInterval()
            _uiState.value = _uiState.value.copy(
                platforms = platforms,
                autoRefreshInterval = interval
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

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = WatchDogApplication.instance
                return SettingsViewModel(app.appContainer.settingsStore) as T
            }
        }
    }
}
