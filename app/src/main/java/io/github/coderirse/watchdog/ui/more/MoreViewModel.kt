package io.github.coderirse.watchdog.ui.more

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.coderirse.watchdog.util.VersionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 检查更新结果（结构化，不携带 UI 文案）：
 * 此前 VM 直接产出中文字符串，无法本地化；文案由 MoreScreen 按 [MoreUiState.checkResult] 类型解析。
 */
sealed interface MoreCheckResult {
    data object Checking : MoreCheckResult
    data class UpToDate(val version: String) : MoreCheckResult
    data class UpdateAvailable(val latestVersion: String) : MoreCheckResult
    data class Failed(val reason: String?) : MoreCheckResult
}

data class MoreUiState(
    val currentVersion: String = "",
    val latestVersion: String? = null,
    val hasUpdate: Boolean = false,
    val isChecking: Boolean = false,
    val checkResult: MoreCheckResult? = null
)

class MoreViewModel(application: Application) : AndroidViewModel(application) {

    private val appVersion: String = run {
        try {
            val pkgInfo = application.packageManager.getPackageInfo(application.packageName, 0)
            pkgInfo.versionName ?: "1.0"
        } catch (_: Exception) { "1.0" }
    }

    private val _uiState = MutableStateFlow(MoreUiState(currentVersion = appVersion))
    val uiState: StateFlow<MoreUiState> = _uiState.asStateFlow()

    fun checkForUpdate() {
        if (_uiState.value.isChecking) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isChecking = true, checkResult = MoreCheckResult.Checking)
            try {
                val latest = fetchLatestVersion()
                val current = appVersion
                val hasUpdate = VersionUtils.isNewer(latest, current)
                _uiState.value = _uiState.value.copy(
                    latestVersion = latest,
                    hasUpdate = hasUpdate,
                    isChecking = false,
                    checkResult = if (hasUpdate) {
                        MoreCheckResult.UpdateAvailable(latest)
                    } else {
                        MoreCheckResult.UpToDate(current)
                    }
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isChecking = false,
                    checkResult = MoreCheckResult.Failed(e.localizedMessage)
                )
            }
        }
    }

    private suspend fun fetchLatestVersion(): String = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL("https://api.github.com/repos/coderirse/WatchDog/releases/latest")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "WatchDog-Android/update-check")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("GitHub API 返回 HTTP ${conn.responseCode}")
            }
            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            json.getString("tag_name").removePrefix("v")
        } finally {
            conn?.disconnect()
        }
    }

    // AndroidViewModel 由默认工厂（CreationExtras.Application）直接构造，无需自定义 Factory
}
