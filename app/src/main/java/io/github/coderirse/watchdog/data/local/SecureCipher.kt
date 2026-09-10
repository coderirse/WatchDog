package io.github.coderirse.watchdog.data.local

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
 * 加密失败（Keystore 不可用等）时返回带 [FALLBACK_PREFIX] 的明文信封，
 * 调用方据此判定发生了降级并持久化告警标记（见 SettingsStore/WebSessionStore）。
 * 判定方式为比较 `encrypt(x) == x`，不使用全局可变状态——
 * 旧实现用全局 `lastDegraded` 标志，多平台并行刷新时存在 TOCTOU：
 * 一次成功加密会把另一次失败的降级标记清掉，导致"明文存储"告警丢失。
 */
object SecureCipher {

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
            // 注意：必须带上 FALLBACK_PREFIX 信封。旧实现直接返回裸明文，
            // 若明文恰以 ENCRYPTED_PREFIX 开头，之后 decrypt 会按密文解析并返回 null，
            // 导致该 API Key 永久不可用。
            FALLBACK_PREFIX + plain
        }
    }

    /**
     * 本次加密是否降级为明文存储。
     * 以"返回值 ≠ 入参"判定：加密成功必然带上了 [ENCRYPTED_PREFIX]，不可能与明文相同。
     */
    fun isDegraded(stored: String, plain: String): Boolean = stored == plain

    /**
     * 解密；兼容三种历史形态：
     * - `enc:v1:` 加密信封 → 正常解密；
     * - `plain:v1:` 降级信封 → 剥壳返回明文（下次保存时自动重试加密）；
     * - 无前缀的裸明文（v1.8.1 之前降级写入的数据）→ 原样返回。
     *
     * 解密失败（密钥被清除 / 数据损坏）返回 null。
     */
    fun decrypt(stored: String): String? {
        if (stored.startsWith(FALLBACK_PREFIX)) return stored.removePrefix(FALLBACK_PREFIX)
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

    /** 生成/取用 Keystore 密钥时的互斥锁（首次生成需避免并发重复创建）。 */
    private val keyLock = Any()

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

    /** 降级（Keystore 不可用）时的明文信封前缀，使明文与密文可判别。 */
    const val FALLBACK_PREFIX = "plain:v1:"

    const val GCM_IV_LENGTH = 12
    const val GCM_TAG_BITS = 128
}
