package io.github.coderirse.watchdog.data.repository

import io.github.coderirse.watchdog.data.model.PlatformType
import io.github.coderirse.watchdog.data.model.QuotaInfo

/**
 * [QuotaRepository] 编排层所需的最小依赖接口（窄接口便于 JVM 单元测试，
 * 避免测试引入 Robolectric/Android Context）。具体实现：
 * SettingsStore / QuotaCacheStore / WebSessionStore。
 */

/** 平台配置读取（已启用平台集合）。 */
interface PlatformConfigSource {
    suspend fun getConfiguredPlatforms(): List<PlatformType>
}

/** 自动刷新间隔读取（分钟）。 */
interface RefreshIntervalSource {
    suspend fun getAutoRefreshInterval(): Int
}

/** 额度数据本地缓存。 */
interface QuotaCache {
    suspend fun get(platform: PlatformType): QuotaInfo?
    suspend fun put(platform: PlatformType, quota: QuotaInfo)
}

/** 网页控制台会话可用性。 */
interface WebSessionAccess {
    suspend fun hasWebSession(platform: PlatformType): Boolean

    /**
     * 读取网页会话令牌（已解密）；未配置或解密失败返回 null。
     *
     * Provider 只依赖本窄接口而非 WebSessionStore 具体类，
     * 便于用纯 JVM 假实现覆盖"会话存在/缺失/失效"等分支（不需要 Android Context）。
     */
    suspend fun getWebSession(platform: PlatformType): String?

    /**
     * 读取随会话保存的浏览器 Cookie 串（WAF 指纹用）；未保存返回 null。
     * 部分平台（DeepSeek 控制台）请求必须带 Cookie 才不被网关拦截。
     */
    suspend fun getWebSessionCookie(platform: PlatformType): String?
}
