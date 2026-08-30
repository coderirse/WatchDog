package io.github.coderirse.watchdog.data.repository

import io.github.coderirse.watchdog.data.model.PlatformType
import io.github.coderirse.watchdog.data.model.QuotaInfo

/**
 * 单平台额度数据源策略接口。
 *
 * 每个平台一个实现（见 providers/ 包），自行负责：读取 API Key / 网页会话、
 * 远程请求、防御性解析、本地月度估算与回退语义。
 * [QuotaRepository] 只做跨平台并行调度、已配置判定、缓存回退等横切逻辑。
 * 新增平台 = 新增一个 Provider 实现并在 AppContainer 注册，不再改动 Repository。
 */
interface PlatformQuotaProvider {
    val platform: PlatformType

    /**
     * 获取该平台最新额度数据。
     * 返回带 errorMessage 的 QuotaInfo 表示业务失败（401/接口变更等）；
     * 抛出的网络/解析异常由 [QuotaRepository] 统一回退本地缓存。
     */
    suspend fun fetch(): QuotaInfo
}
