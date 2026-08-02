package com.example.watchdog.data.local

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
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
        } catch (_: Exception) {
            // Keystore 不可用时回退明文存储，避免用户被锁在门外（极少发生）
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

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEYSTORE_ALIAS = "watchdog_api_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val ENCRYPTED_PREFIX = "enc:v1:"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_BITS = 128
    }
}
