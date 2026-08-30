package io.github.coderirse.watchdog.data.repository

import io.github.coderirse.watchdog.data.local.QuotaCacheStore
import io.github.coderirse.watchdog.data.local.SettingsStore
import io.github.coderirse.watchdog.data.local.WebSessionStore
import io.github.coderirse.watchdog.data.model.PlatformType
import io.github.coderirse.watchdog.data.model.QuotaInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * 额度数据编排层：跨平台并行调度、已配置判定、成功写缓存、异常回退缓存。
 *
 * 各平台的具体抓取/解析/估算逻辑在 [PlatformQuotaProvider] 实现中（providers/ 包），
 * 新增平台只需实现一个 Provider 并在 AppContainer 注册。
 */
class QuotaRepository(
    private val settingsStore: SettingsStore,
    private val cacheStore: QuotaCacheStore,
    private val webSessionStore: WebSessionStore,
    providers: List<PlatformQuotaProvider>
) {
    private val providerMap: Map<PlatformType, PlatformQuotaProvider> = providers.associateBy { it.platform }

    suspend fun fetchAllQuotas(): List<QuotaInfo> = coroutineScope {
        val configuredPlatforms = settingsStore.getConfiguredPlatforms().toMutableSet()
        // 支持网页会话的平台：即使未填 API Key，只要配置了会话（能读控制台数据）也算已配置，
        // 否则登录了控制台但没填 API Key 的平台会整卡消失（真机实测：DeepSeek 登录后仪表盘空白）
        for (platform in PlatformType.entries) {
            if (platform.supportsConsoleSession &&
                runCatching { webSessionStore.hasWebSession(platform) }.getOrDefault(false)
            ) {
                configuredPlatforms.add(platform)
            }
        }
        val allPlatforms = PlatformType.entries

        allPlatforms.map { platform ->
            async {
                if (platform !in configuredPlatforms) {
                    QuotaInfo.notConfigured(platform)
                } else {
                    fetchPlatformQuota(platform)
                }
            }
        }.map { it.await() }
    }

    suspend fun fetchPlatformQuota(platform: PlatformType): QuotaInfo {
        val provider = providerMap[platform] ?: return QuotaInfo.notConfigured(platform)
        return try {
            val result = provider.fetch()
            // 成功后写入缓存，供断网时回退展示
            if (result.errorMessage == null) {
                runCatching { cacheStore.put(platform, result) }
            }
            result
        } catch (e: CancellationException) {
            throw e // 协程取消必须向上传播，不能当作错误吞掉
        } catch (e: Exception) {
            // 网络/解析异常时优先展示上次缓存的数据，并标记为缓存数据
            val cached = runCatching { cacheStore.get(platform) }.getOrNull()
            if (cached != null) {
                cached.copy(isConfigured = true, errorMessage = null, isStale = true)
            } else {
                QuotaInfo.error(platform, messageFor(e))
            }
        }
    }

    private fun messageFor(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "无法连接服务器，请检查网络"
        is java.net.SocketTimeoutException -> "请求超时，请稍后重试"
        is java.io.IOException -> "网络请求失败"
        else -> e.localizedMessage ?: "未知错误"
    }
}
