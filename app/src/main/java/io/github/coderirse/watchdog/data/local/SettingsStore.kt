package io.github.coderirse.watchdog.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import io.github.coderirse.watchdog.data.model.PlatformType
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
            prefs.edit { putString(getApiKeyKey(platform), encryptApiKey(apiKey)) }
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
            val stored = prefs.getString(getApiKeyKey(platform), "") ?: ""
            stored.ifBlank { null }?.let { decryptApiKey(it) }?.ifBlank { null }
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
            // Kimi Code 与 Kimi 开放平台为独立产品，API Key 独立存储，互不复用
            PlatformType.VOLCENGINE_ARK -> "volcengine_ark_api_key"
            PlatformType.KIMI_CODE -> "kimi_code_api_key"
            PlatformType.MIMO -> "mimo_api_key"
        }
    }

    private fun getEnabledKey(platform: PlatformType): String {
        return when (platform) {
            PlatformType.DEEPSEEK -> "deepseek_enabled"
            PlatformType.KIMI -> "kimi_enabled"
            PlatformType.GLM -> "glm_enabled"
            PlatformType.SILICONFLOW -> "siliconflow_enabled"
            PlatformType.VOLCENGINE_ARK -> "volcengine_ark_enabled"
            PlatformType.KIMI_CODE -> "kimi_code_enabled"
            PlatformType.MIMO -> "mimo_enabled"
        }
    }

    // ===== API Key 加密存储（Android Keystore AES-GCM，实现见 SecureCipher） =====

    private fun encryptApiKey(plain: String): String {
        val encrypted = SecureCipher.encrypt(plain)
        if (SecureCipher.lastDegraded) {
            // Keystore 不可用时已回退明文存储，记录告警日志 + 持久化降级标记，
            // 供设置页提示用户敏感数据当前未加密。
            Log.w(TAG, "Android Keystore 不可用，API Key 回退为明文存储")
            markEncryptionDegraded()
        }
        return encrypted
    }

    private fun decryptApiKey(stored: String): String? = SecureCipher.decrypt(stored)

    // ===== 本月用量追踪 =====

    /**
     * 记录最新余额并返回本月估算用量（增量累计方案）。
     *
     * 每次刷新把"较上次余额的下降量"（真实消耗）累加进本月用量；余额上升
     * （充值/赠送）不计用量、也不重置累计——修复旧"月初余额-当前余额"方案
     * 月中充值后整个剩余月份用量恒为 0 的失真问题。跨月自动清零重新累计。
     *
     * 已知局限：两次刷新之间"先消耗后充值"的净变化为升时，该区间的消耗会丢失。
     */
    suspend fun recordBalanceAndGetMonthlyUsage(
        platform: PlatformType,
        currentBalance: Double
    ): Double {
        return withContext(Dispatchers.IO) {
            val now = java.util.Calendar.getInstance()
            // 用 年*12+月 作为月份标识，避免跨年时误判为同一月份
            val currentMonth = now.get(java.util.Calendar.YEAR) * 12 + now.get(java.util.Calendar.MONTH)
            val prefix = getPrefix(platform)
            val monthKey = "${prefix}_usage_month"
            val accumKey = "${prefix}_usage_accum"
            val lastKey = "${prefix}_last_balance"

            val storedMonth = prefs.getInt(monthKey, -1)
            val accum: Double
            if (storedMonth != currentMonth) {
                // 新月：从零开始累计，基准余额取当前值
                accum = 0.0
                prefs.edit {
                    putInt(monthKey, currentMonth)
                    putString(accumKey, "0.0")
                    putString(lastKey, currentBalance.toString())
                }
            } else {
                val prevAccum = prefs.getString(accumKey, null)?.toDoubleOrNull() ?: 0.0
                val lastBalance = prefs.getString(lastKey, null)?.toDoubleOrNull() ?: currentBalance
                val delta = lastBalance - currentBalance
                accum = if (delta > 0) prevAccum + delta else prevAccum
                prefs.edit {
                    putString(accumKey, accum.toString())
                    putString(lastKey, currentBalance.toString())
                }
            }
            accum.coerceAtLeast(0.0)
        }
    }

    private fun getPrefix(platform: PlatformType): String {
        return when (platform) {
            PlatformType.DEEPSEEK -> "deepseek"
            PlatformType.KIMI -> "kimi"
            PlatformType.GLM -> "glm"
            PlatformType.SILICONFLOW -> "siliconflow"
            PlatformType.VOLCENGINE_ARK -> "volcengine_ark"
            PlatformType.KIMI_CODE -> "kimi_code"
            PlatformType.MIMO -> "mimo"
        }
    }

    // ===== 控制台余额探测路径缓存（MiMo 等需运行时探测候选路径的平台） =====

    /** 保存某平台最近一次探测命中的余额路径，下次刷新优先直用。 */
    suspend fun saveProbePath(platform: PlatformType, path: String) {
        withContext(Dispatchers.IO) {
            prefs.edit { putString("${getPrefix(platform)}_probe_path", path) }
        }
    }

    /** 读取某平台缓存的探测命中路径；无则 null。 */
    suspend fun getProbePath(platform: PlatformType): String? {
        return withContext(Dispatchers.IO) {
            prefs.getString("${getPrefix(platform)}_probe_path", null)?.takeIf { it.isNotBlank() }
        }
    }

    // ===== 手动初始余额（估算模式平台用，当前仅火山方舟） =====

    /**
     * 保存用户手动填写的初始余额；传 null 表示清除。
     * 非密钥数据，不加密、不经 Keystore；以字符串存储避免 Float 精度损失。
     */
    suspend fun saveInitialBalance(platform: PlatformType, balance: Double?) {
        withContext(Dispatchers.IO) {
            prefs.edit {
                val key = "${getPrefix(platform)}_initial_balance"
                if (balance == null) remove(key) else putString(key, balance.toString())
            }
        }
    }

    /** 读取用户手动填写的初始余额；未填写返回 null */
    suspend fun getInitialBalance(platform: PlatformType): Double? {
        return withContext(Dispatchers.IO) {
            prefs.getString("${getPrefix(platform)}_initial_balance", null)?.toDoubleOrNull()
        }
    }

    // ===== 主题模式 =====

    /** 保存主题模式："system" / "light" / "dark"，非法值回退 "system" */
    suspend fun saveThemeMode(mode: String) {
        withContext(Dispatchers.IO) {
            val valid = if (mode in THEME_MODES) mode else THEME_MODE_SYSTEM
            prefs.edit { putString(KEY_THEME_MODE, valid) }
        }
    }

    /** 读取主题模式，默认 "system" */
    suspend fun getThemeMode(): String {
        return withContext(Dispatchers.IO) {
            prefs.getString(KEY_THEME_MODE, THEME_MODE_SYSTEM)
                ?.takeIf { it in THEME_MODES } ?: THEME_MODE_SYSTEM
        }
    }

    /**
     * 同步读取主题模式，默认 "system"。
     * 供 Application 启动路径使用，避免在 AppContainer 构造时用 runBlocking 阻塞主线程；
     * SharedPreferences 的 getString 本身是同步且轻量的。
     */
    fun getThemeModeSync(): String {
        return prefs.getString(KEY_THEME_MODE, THEME_MODE_SYSTEM)
            ?.takeIf { it in THEME_MODES } ?: THEME_MODE_SYSTEM
    }

    // ===== 加密降级状态（Keystore 不可用时 API Key 回退明文） =====

    /** 标记加密已降级为明文存储（幂等，持久化供设置页提示）。 */
    private fun markEncryptionDegraded() {
        runCatching {
            prefs.edit { putBoolean(KEY_ENCRYPTION_DEGRADED, true) }
        }
    }

    /** 读取加密是否已降级为明文存储。 */
    suspend fun isEncryptionDegraded(): Boolean {
        return withContext(Dispatchers.IO) {
            prefs.getBoolean(KEY_ENCRYPTION_DEGRADED, false)
        }
    }

    // ===== 余额低水位预警 =====

    suspend fun saveBalanceAlertEnabled(enabled: Boolean) {
        withContext(Dispatchers.IO) {
            prefs.edit { putBoolean(KEY_BALANCE_ALERT_ENABLED, enabled) }
        }
    }

    suspend fun isBalanceAlertEnabled(): Boolean {
        return withContext(Dispatchers.IO) {
            prefs.getBoolean(KEY_BALANCE_ALERT_ENABLED, false)
        }
    }

    suspend fun saveBalanceAlertThreshold(threshold: Double) {
        withContext(Dispatchers.IO) {
            prefs.edit { putString(KEY_BALANCE_ALERT_THRESHOLD, threshold.toString()) }
        }
    }

    /** 读取余额预警阈值（CNY），非法值回退默认值。 */
    suspend fun getBalanceAlertThreshold(): Double {
        return withContext(Dispatchers.IO) {
            prefs.getString(KEY_BALANCE_ALERT_THRESHOLD, null)
                ?.toDoubleOrNull()
                ?.takeIf { it >= 0.0 }
                ?: DEFAULT_BALANCE_ALERT_THRESHOLD
        }
    }

    /** 保存余额预警比例阈值（百分比 0~100），超出范围自动钳制。 */
    suspend fun saveBalanceAlertFraction(percent: Double) {
        withContext(Dispatchers.IO) {
            prefs.edit { putString(KEY_BALANCE_ALERT_FRACTION, percent.coerceIn(0.0, 100.0).toString()) }
        }
    }

    /** 读取余额预警比例阈值（百分比 0~100），非法值回退默认值。 */
    suspend fun getBalanceAlertFraction(): Double {
        return withContext(Dispatchers.IO) {
            prefs.getString(KEY_BALANCE_ALERT_FRACTION, null)
                ?.toDoubleOrNull()
                ?.coerceIn(0.0, 100.0)
                ?: DEFAULT_BALANCE_ALERT_FRACTION
        }
    }

    private companion object {
        const val KEY_THEME_MODE = "theme_mode"
        const val THEME_MODE_SYSTEM = "system"
        val THEME_MODES = setOf("system", "light", "dark")
        const val KEY_ENCRYPTION_DEGRADED = "encryption_degraded"
        const val KEY_BALANCE_ALERT_ENABLED = "balance_alert_enabled"
        const val KEY_BALANCE_ALERT_THRESHOLD = "balance_alert_threshold"
        const val DEFAULT_BALANCE_ALERT_THRESHOLD = 10.0
        const val KEY_BALANCE_ALERT_FRACTION = "balance_alert_fraction"
        const val DEFAULT_BALANCE_ALERT_FRACTION = 20.0
        const val TAG = "SettingsStore"
    }
}
