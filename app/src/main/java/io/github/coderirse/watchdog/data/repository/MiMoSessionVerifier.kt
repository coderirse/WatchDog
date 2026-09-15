package io.github.coderirse.watchdog.data.repository

import io.github.coderirse.watchdog.data.api.MiMoConsoleApi
import io.github.coderirse.watchdog.data.local.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MiMo 网页会话有效性校验（**App 侧**，走与抓取数据完全相同的请求路径）。
 *
 * ## 为什么需要它
 *
 * 原实现把"凭证是否有效"的判断交给登录页的页面内 `fetch`（同源请求），
 * 但这与 App 实际发请求的方式并不等价，且引入了两个真实缺陷：
 *
 * 1. **用 `WebView.url` 决定是否校验**：OAuth 回跳瞬间 `url` 可能还是账号域（甚至为空），
 *    代码便走"不在平台域 → 出现即有效"的捷径直接保存凭证——这正是"登录成功但抓不到"的来源；
 * 2. **页面内 fetch 与 OkHttp 不同源**：页面内请求自动携带该源的全部 Cookie 与浏览器指纹，
 *    而 App 侧只按我们拼装的请求头发送；页面校验通过并不保证 App 侧通过。
 *
 * 改为 App 侧校验后，判断依据与真实数据请求完全一致，无需再猜 `WebView.url`。
 */
class MiMoSessionVerifier(
    private val mimoConsoleApi: MiMoConsoleApi,
    private val settingsStore: SettingsStore
) {

    /** 校验结果：网络层失败（无法判定）与业务上被拒（401）必须区分。 */
    sealed interface Result {
        /** 服务端接受该会话（HTTP 200）。 */
        data object Valid : Result

        /** 服务端明确拒绝（401/403），凭证无效或已过期。 */
        data object Rejected : Result

        /** 网络异常/无法判定，调用方应稍后重试而不是判定为无效。 */
        data class Inconclusive(val message: String?) : Result
    }

    suspend fun verify(phToken: String, cookie: String?): Result = withContext(Dispatchers.IO) {
        val response = runCatching {
            mimoConsoleApi.getTokenPlanDetail(phToken, cookie)
        }.getOrNull() ?: return@withContext Result.Inconclusive("网络请求失败")

        when {
            response.isSuccessful -> Result.Valid
            response.code() == 401 || response.code() == 403 -> Result.Rejected
            else -> Result.Inconclusive("HTTP ${response.code()}")
        }
    }

    /** 供诊断使用：把最近一次探测命中的余额路径缓存清掉（换账号后路径可能不同）。 */
    suspend fun resetCachedProbePath(platform: io.github.coderirse.watchdog.data.model.PlatformType) {
        runCatching { settingsStore.saveProbePath(platform, "") }
    }
}
