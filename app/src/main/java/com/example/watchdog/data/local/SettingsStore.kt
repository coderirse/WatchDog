package com.example.watchdog.data.local

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import com.example.watchdog.data.model.PlatformType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SettingsStore(
    private val context: Context
) {
    private val keyLock = Any()

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
        }
    }

    // ===== API Key 加密存储（Android Keystore AES-GCM） =====

    private fun encryptApiKey(plain: String): String {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val payload = ByteBuffer.allocate(iv.size + encrypted.size)
                .put(iv)
                .put(encrypted)
                .array()
            ENCRYPTED_PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP)
        } catch (e: Exception) {
            // Keystore 不可用时回退明文存储，避免用户被锁在门外（极少发生）。
            // 记录告警日志 + 持久化降级标记，供设置页提示用户敏感数据当前未加密。
            Log.w(TAG, "Android Keystore 不可用，API Key 回退为明文存储", e)
            markEncryptionDegraded()
            plain
        }
    }

    private fun decryptApiKey(stored: String): String? {
        // 旧版本未加密的明文数据直接返回，下次保存时自动迁移为密文
        if (!stored.startsWith(ENCRYPTED_PREFIX)) return stored
        return try {
            val raw = Base64.decode(stored.removePrefix(ENCRYPTED_PREFIX), Base64.NO_WRAP)
            if (raw.size <= GCM_IV_LENGTH) return null
            val iv = raw.copyOfRange(0, GCM_IV_LENGTH)
            val ciphertext = raw.copyOfRange(GCM_IV_LENGTH, raw.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun getOrCreateSecretKey(): SecretKey = synchronized(keyLock) {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        generator.generateKey()
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
            // 用 年*12+月 作为月份标识，避免跨年时误判为同一月份
            val currentMonth = now.get(java.util.Calendar.YEAR) * 12 + now.get(java.util.Calendar.MONTH)
            val storedMonth = prefs.getInt("${getPrefix(platform)}_month_start_month", -1)
            val startBalanceKey = "${getPrefix(platform)}_month_start_balance"

            val startBalance: Double
            if (storedMonth != currentMonth) {
                // 新月：重置起始余额为当前余额（以字符串存储，避免 Float 精度损失）
                startBalance = currentBalance
                prefs.edit {
                    putString(startBalanceKey, currentBalance.toString())
                    putInt("${getPrefix(platform)}_month_start_month", currentMonth)
                }
            } else {
                // 同月：优先读新版字符串；旧版 Float 数据兜底迁移
                val storedString = prefs.getString(startBalanceKey, null)
                startBalance = if (storedString != null) {
                    storedString.toDoubleOrNull() ?: currentBalance
                } else {
                    val legacy = prefs.getFloat(startBalanceKey, Float.NaN)
                    if (legacy.isNaN()) currentBalance else legacy.toDouble()
                }
            }

            // 本月用量 = 月初余额 - 当前余额
            val usage = startBalance - currentBalance
            if (usage < 0) 0.0 else usage
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
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEYSTORE_ALIAS = "watchdog_api_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val ENCRYPTED_PREFIX = "enc:v1:"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_BITS = 128
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
