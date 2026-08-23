package com.example.watchdog.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.watchdog.data.model.PlatformType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 网页控制台会话凭证存储（"爬取"数据源的基础设施）。
 *
 * 部分平台（如小米 MiMo、DeepSeek 控制台）没有"仅凭 API Key"的余额/用量接口，
 * 但网页控制台的内部 JSON 接口可以返回更完整的数据（真实余额、按模型/日期的用量等）。
 * 会话凭证由 WebLoginActivity 在用户完成网页登录后自动抓取：
 * - DeepSeek：localStorage 的 userToken（Bearer 鉴权）+ 浏览器 Cookie（WAF 指纹）
 * - MiMo：Cookie 中的 api-platform_ph（请求头鉴权）
 * 经 [SecureCipher]（Android Keystore AES-GCM）加密存储。
 *
 * 注意：会话会过期（MiMo 官方 Cookie 有效期 24 小时；DeepSeek userToken 数天到数周），
 * 失效后由界面提示重新进入登录页；登录页 WebView 的会话是持久化的，
 * 若仍有效则重新抓取凭证无需用户再次输入账号。
 */
class WebSessionStore(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        "watchdog_web_sessions",
        Context.MODE_PRIVATE
    )

    /** 加密降级标记与 SettingsStore 共用同一文件，保证设置页告警一致。 */
    private val settingsPrefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        "watchdog_settings",
        Context.MODE_PRIVATE
    )

    suspend fun saveWebSession(platform: PlatformType, token: String, cookie: String? = null) {
        withContext(Dispatchers.IO) {
            prefs.edit {
                putString(tokenKey(platform), SecureCipher.encrypt(token))
                if (cookie != null) putString(cookieKey(platform), SecureCipher.encrypt(cookie))
            }
            if (SecureCipher.lastDegraded) {
                settingsPrefs.edit { putBoolean("encryption_degraded", true) }
            }
        }
    }

    suspend fun removeWebSession(platform: PlatformType) {
        withContext(Dispatchers.IO) {
            prefs.edit {
                remove(tokenKey(platform))
                remove(cookieKey(platform))
            }
        }
    }

    /** 读取网页会话令牌；未配置或解密失败返回 null。 */
    suspend fun getWebSession(platform: PlatformType): String? {
        return withContext(Dispatchers.IO) {
            val stored = prefs.getString(tokenKey(platform), "") ?: ""
            stored.ifBlank { null }?.let { SecureCipher.decrypt(it) }?.ifBlank { null }
        }
    }

    /** 读取随会话保存的浏览器 Cookie 串（WAF 指纹用）；未保存返回 null。 */
    suspend fun getWebSessionCookie(platform: PlatformType): String? {
        return withContext(Dispatchers.IO) {
            val stored = prefs.getString(cookieKey(platform), "") ?: ""
            stored.ifBlank { null }?.let { SecureCipher.decrypt(it) }?.ifBlank { null }
        }
    }

    /** 是否配置了网页会话令牌（用于设置页展示状态）。 */
    suspend fun hasWebSession(platform: PlatformType): Boolean {
        return getWebSession(platform) != null
    }

    private fun tokenKey(platform: PlatformType): String = "web_session_${platform.name}"

    private fun cookieKey(platform: PlatformType): String = "web_session_cookie_${platform.name}"
}
