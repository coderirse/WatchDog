package com.example.watchdog.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.watchdog.data.model.PlatformType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SettingsStore(
    private val context: Context
) {
    private val prefs: SharedPreferences
        get() = context.applicationContext.getSharedPreferences(
            "watchdog_settings",
            Context.MODE_PRIVATE
        )

    suspend fun saveApiKey(platform: PlatformType, apiKey: String) {
        withContext(Dispatchers.IO) {
            prefs.edit { putString(getApiKeyKey(platform), apiKey) }
        }
    }

    suspend fun removeApiKey(platform: PlatformType) {
        withContext(Dispatchers.IO) {
            prefs.edit { remove(getApiKeyKey(platform)) }
        }
    }

    suspend fun setEnabled(platform: PlatformType, enabled: Boolean) {
        withContext(Dispatchers.IO) {
            prefs.edit { putBoolean(getEnabledKey(platform), enabled) }
        }
    }

    suspend fun getConfiguredPlatforms(): List<PlatformType> {
        return withContext(Dispatchers.IO) {
            PlatformType.entries.filter { platform ->
                val apiKey = prefs.getString(getApiKeyKey(platform), "") ?: ""
                val enabled = prefs.getBoolean(getEnabledKey(platform), false)
                apiKey.isNotBlank() && enabled
            }
        }
    }

    suspend fun getApiKey(platform: PlatformType): String? {
        return withContext(Dispatchers.IO) {
            val apiKey = prefs.getString(getApiKeyKey(platform), "") ?: ""
            apiKey.ifBlank { null }
        }
    }

    suspend fun isEnabled(platform: PlatformType): Boolean {
        return withContext(Dispatchers.IO) {
            prefs.getBoolean(getEnabledKey(platform), false)
        }
    }

    suspend fun saveAutoRefreshInterval(minutes: Int) {
        withContext(Dispatchers.IO) {
            prefs.edit { putString("auto_refresh_interval", minutes.toString()) }
        }
    }

    suspend fun getAutoRefreshInterval(): Int {
        return withContext(Dispatchers.IO) {
            prefs.getString("auto_refresh_interval", "5")?.toIntOrNull() ?: 5
        }
    }

    private fun getApiKeyKey(platform: PlatformType): String {
        return when (platform) {
            PlatformType.DEEPSEEK -> "deepseek_api_key"
            PlatformType.KIMI -> "kimi_api_key"
            PlatformType.GLM -> "glm_api_key"
            PlatformType.SILICONFLOW -> "siliconflow_api_key"
        }
    }

    private fun getEnabledKey(platform: PlatformType): String {
        return when (platform) {
            PlatformType.DEEPSEEK -> "deepseek_enabled"
            PlatformType.KIMI -> "kimi_enabled"
            PlatformType.GLM -> "glm_enabled"
            PlatformType.SILICONFLOW -> "siliconflow_enabled"
        }
    }

    // ===== 本月用量追踪 =====

    /**
     * 保存月初余额和月份标记
     * 如果月份变了，月初余额会重置
     */
    suspend fun recordBalanceAndGetMonthlyUsage(
        platform: PlatformType,
        currentBalance: Double
    ): Double {
        return withContext(Dispatchers.IO) {
            val now = java.util.Calendar.getInstance()
            val currentMonth = now.get(java.util.Calendar.MONTH) // 0-11
            val storedMonth = prefs.getInt("${getPrefix(platform)}_month_start_month", -1)
            val startBalanceKey = "${getPrefix(platform)}_month_start_balance"

            val startBalance: Float
            if (storedMonth != currentMonth) {
                // 新月：重置起始余额为当前余额
                startBalance = currentBalance.toFloat()
                prefs.edit {
                    putFloat(startBalanceKey, startBalance)
                    putInt("${getPrefix(platform)}_month_start_month", currentMonth)
                }
            } else {
                // 同月：读取已保存的起始余额
                startBalance = prefs.getFloat(startBalanceKey, currentBalance.toFloat())
            }

            // 本月用量 = 月初余额 - 当前余额
            val usage = (startBalance - currentBalance).toDouble()
            if (usage < 0) 0.0 else usage
        }
    }

    private fun getPrefix(platform: PlatformType): String {
        return when (platform) {
            PlatformType.DEEPSEEK -> "deepseek"
            PlatformType.KIMI -> "kimi"
            PlatformType.GLM -> "glm"
            PlatformType.SILICONFLOW -> "siliconflow"
        }
    }
}
