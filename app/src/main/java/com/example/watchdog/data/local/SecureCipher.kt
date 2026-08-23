package com.example.watchdog.data.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 基于 Android Keystore(AES-256-GCM) 的敏感数据加解密工具。
 *
 * 由 SettingsStore 提取而来，供 API Key 与网页会话令牌等敏感字段共用：
 * 同一 Keystore 别名加密的数据可以互相解密（同属本应用进程）。
 *
 * 加密失败（Keystore 不可用等）时返回明文并置 [lastDegraded]，
 * 调用方应负责持久化降级标记并在设置页提示用户。
 */
object SecureCipher {

    /** 最近一次加密是否发生降级（回退明文存储）。 */
    @Volatile
    var lastDegraded: Boolean = false
        private set

    private val keyLock = Any()

    fun encrypt(plain: String): String {
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
            lastDegraded = true
            plain
        }
    }

    /**
     * 解密；旧版本未加密的明文数据直接返回（下次保存时自动迁移为密文）。
     * 解密失败（密钥被清除 / 数据损坏）返回 null。
     */
    fun decrypt(stored: String): String? {
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

    const val ANDROID_KEYSTORE = "AndroidKeyStore"
    const val KEYSTORE_ALIAS = "watchdog_api_key"
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val ENCRYPTED_PREFIX = "enc:v1:"
    const val GCM_IV_LENGTH = 12
    const val GCM_TAG_BITS = 128
}
