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

/** 额度数据本地缓存。 */
interface QuotaCache {
    suspend fun get(platform: PlatformType): QuotaInfo?
    suspend fun put(platform: PlatformType, quota: QuotaInfo)
}

/** 网页控制台会话可用性。 */
interface WebSessionAccess {
    suspend fun hasWebSession(platform: PlatformType): Boolean
}
