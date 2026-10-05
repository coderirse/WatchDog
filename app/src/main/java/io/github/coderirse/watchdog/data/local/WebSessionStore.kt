package io.github.coderirse.watchdog.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.coderirse.watchdog.data.model.PlatformType
import io.github.coderirse.watchdog.data.repository.WebSessionAccess
import io.github.coderirse.watchdog.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

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
class WebSessionStore(context: Context) : WebSessionAccess {

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
            val storedToken = SecureCipher.encrypt(token)
            val storedCookie = cookie?.let { SecureCipher.encrypt(it) }
            // 诊断：会话保存是"登录成功但抓不到数据"最可疑的一环，
            // 必须能看出写入了什么（只记长度与键名，绝不记凭证明文）
            DebugLog.d(
                TAG,
                "saveWebSession ${platform.name}: tokenPlain=${token.length} " +
                    "tokenStored=${storedToken.length} cookiePlain=${cookie?.length ?: 0} " +
                    "cookieStored=${storedCookie?.length ?: 0} degraded=${SecureCipher.isDegraded(storedToken, token)}"
            )
            val committed = prefs.edit().apply {
                putString(tokenKey(platform), storedToken)
                if (storedCookie != null) {
                    putString(cookieKey(platform), storedCookie)
                } else {
                    // 本次未携带 Cookie（如手动粘贴 token 的换账号路径）必须清掉旧 Cookie：
                    // 残留的旧账号 Cookie 会与新 token 混发，网关按整串 Cookie 判定登录态，
                    // 要么 401"会话已过期"，要么按 Cookie 识别返回旧账号数据
                    remove(cookieKey(platform))
                }
            }.commit()
            DebugLog.d(
                TAG,
                "saveWebSession ${platform.name}: committed=$committed " +
                    "readBack=${prefs.getString(tokenKey(platform), null)?.length ?: -1}"
            )
            // 降级判定基于本次返回值与明文的比较（见 SecureCipher.isDegraded），
            // 不用全局标志，避免多平台并行保存时的竞态把告警清掉
            if (SecureCipher.isDegraded(storedToken, token) ||
                (cookie != null && storedCookie != null && SecureCipher.isDegraded(storedCookie, cookie))
            ) {
                settingsPrefs.edit { putBoolean(SettingsStore.KEY_ENCRYPTION_DEGRADED, true) }
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

    /**
     * 彻底清除某平台的网页会话：加密凭证 + Kimi 控制台快照 + **该平台域的 WebView 登录态**
     * （Cookie 与 localStorage/sessionStorage）。
     *
     * 为什么必须连 WebView 一起清：登录页复用的是持久化登录态，
     * 只删我们自己的加密存储而不清 WebView，用户重新打开登录页时会**自动带着旧账号登录态**
     * 被秒抓凭证——表现为"无法换账号"（用户实机反馈的真实缺口）。
     *
     * 只按平台自己的域清理（[android.webkit.WebStorage.deleteOrigin]），刻意不用
     * `deleteAllData()`：后者会连带清掉其它平台的 WebView 登录态，
     * 用户只想换一个平台却发现其它平台也要重新登录。
     */
    suspend fun removeWebSessionFully(platform: PlatformType) {
        removeWebSession(platform)
        if (platform == PlatformType.KIMI) {
            withContext(Dispatchers.IO) { prefs.edit { remove(snapshotKey(platform)) } }
        }
        val origin = platform.consoleOrigin() ?: return
        withContext(Dispatchers.Main) {
            runCatching {
                val cookieManager = android.webkit.CookieManager.getInstance()
                // CookieManager 没有"按域删除"的 API。旧实现 removeAllCookies(null) 清掉
                // 所有域的 Cookie——换一个平台的账号会连带登出其它平台的 WebView 登录态，
                // 与本法注释宣称的"只按平台自己的域清理"相矛盾。改为枚举平台域（含共享
                // 父域）可见的 Cookie 逐条置过期删除；跨根域的账号体系 Cookie 与
                // 非根路径 Cookie 不在 best-effort 清理范围内。
                val host = runCatching { java.net.URI(origin).host }.getOrNull()
                val urls = buildList {
                    add(origin)
                    if (host != null && host.count { it == '.' } >= 2) {
                        add(origin.replaceFirst(host, host.substringAfter('.')))
                    }
                }
                for (url in urls) {
                    val cookieHeader = cookieManager.getCookie(url) ?: continue
                    for (pair in cookieHeader.split(";")) {
                        val name = pair.substringBefore('=').trim()
                        if (name.isEmpty()) continue
                        cookieManager.setCookie(
                            url,
                            "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0"
                        )
                    }
                }
                cookieManager.flush()
                android.webkit.WebStorage.getInstance().deleteOrigin(origin)
            }
        }
    }

    /** 该平台网页控制台的源（供清除 WebView 登录态用）；不支持会话的平台返回 null。 */
    private fun PlatformType.consoleOrigin(): String? = when (this) {
        PlatformType.MIMO -> "https://platform.xiaomimimo.com"
        PlatformType.DEEPSEEK -> "https://platform.deepseek.com"
        PlatformType.KIMI -> "https://platform.kimi.com"
        else -> null
    }

    /** 读取网页会话令牌；未配置或解密失败返回 null。 */
    override suspend fun getWebSession(platform: PlatformType): String? {
        return withContext(Dispatchers.IO) {
            val stored = prefs.getString(tokenKey(platform), "") ?: ""
            val plain = stored.ifBlank { null }?.let { SecureCipher.decrypt(it) }?.ifBlank { null }
            DebugLog.d(
                TAG,
                "getWebSession ${platform.name}: storedLen=${stored.length} " +
                    "decryptedLen=${plain?.length ?: -1}"
            )
            plain
        }
    }

    /** 读取随会话保存的浏览器 Cookie 串（WAF 指纹用）；未保存返回 null。 */
    override suspend fun getWebSessionCookie(platform: PlatformType): String? {
        return withContext(Dispatchers.IO) {
            val stored = prefs.getString(cookieKey(platform), "") ?: ""
            stored.ifBlank { null }?.let { SecureCipher.decrypt(it) }?.ifBlank { null }
        }
    }

    /** 是否配置了网页会话令牌（用于设置页展示状态）。 */
    override suspend fun hasWebSession(platform: PlatformType): Boolean {
        return getWebSession(platform) != null
    }

    private fun tokenKey(platform: PlatformType): String = "web_session_${platform.name}"

    private fun cookieKey(platform: PlatformType): String = "web_session_cookie_${platform.name}"

    // ===== Kimi 控制台快照（登录时从页面 DOM 采集的余额/消费，跨模块显示）=====

    suspend fun saveKimiSnapshot(balance: String?, month: String?, total: String?) {
        withContext(Dispatchers.IO) {
            val json = JSONObject()
                .put("balance", balance ?: "")
                .put("month", month ?: "")
                .put("total", total ?: "")
                .toString()
            val stored = SecureCipher.encrypt(json)
            prefs.edit { putString(snapshotKey(PlatformType.KIMI), stored) }
            if (SecureCipher.isDegraded(stored, json)) {
                settingsPrefs.edit { putBoolean(SettingsStore.KEY_ENCRYPTION_DEGRADED, true) }
            }
        }
    }

    suspend fun getKimiSnapshot(): KimiSnapshot? {
        return withContext(Dispatchers.IO) {
            val stored = prefs.getString(snapshotKey(PlatformType.KIMI), "") ?: ""
            val json = stored.ifBlank { null }?.let { SecureCipher.decrypt(it) } ?: return@withContext null
            runCatching {
                val o = JSONObject(json)
                KimiSnapshot(
                    balance = o.optString("balance").ifBlank { null },
                    month = o.optString("month").ifBlank { null },
                    total = o.optString("total").ifBlank { null }
                )
            }.getOrNull()
        }
    }

    private fun snapshotKey(platform: PlatformType): String = "web_session_snapshot_${platform.name}"

    data class KimiSnapshot(val balance: String?, val month: String?, val total: String?)

    private companion object {
        const val TAG = "WatchDogSession"
    }
}
