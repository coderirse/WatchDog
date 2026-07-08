package com.example.watchdog.ui.more

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.watchdog.data.model.PlatformType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class MoreUiState(
    val currentVersion: String = "1.2.0",
    val latestVersion: String? = null,
    val hasUpdate: Boolean = false,
    val isChecking: Boolean = false,
    val checkResult: String = "" // 空=未检查, 其他=提示文字
)

class MoreViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(MoreUiState())
    val uiState: StateFlow<MoreUiState> = _uiState.asStateFlow()

    fun checkForUpdate() {
        if (_uiState.value.isChecking) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isChecking = true, checkResult = "正在检查...")
            try {
                val latest = fetchLatestVersion()
                val current = _uiState.value.currentVersion
                val hasUpdate = isNewer(latest, current)
                _uiState.value = _uiState.value.copy(
                    latestVersion = latest,
                    hasUpdate = hasUpdate,
                    isChecking = false,
                    checkResult = if (hasUpdate) "发现新版本 v$latest，请前往 GitHub 下载"
                    else "已是最新版本 v$current"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isChecking = false,
                    checkResult = "检查失败: ${e.localizedMessage ?: "网络错误"}"
                )
            }
        }
    }

    private suspend fun fetchLatestVersion(): String = withContext(Dispatchers.IO) {
        val url = URL("https://api.github.com/repos/coderirse/WatchDog/releases/latest")
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        val body = conn.inputStream.bufferedReader().readText()
        val json = JSONObject(body)
        json.getString("tag_name").removePrefix("v")
    }

    private fun isNewer(latest: String, current: String): Boolean {
        val l = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val c = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(l.size, c.size)) {
            val lv = l.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (lv > cv) return true
            if (lv < cv) return false
        }
        return false
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MoreViewModel() as T
        }
    }
}
