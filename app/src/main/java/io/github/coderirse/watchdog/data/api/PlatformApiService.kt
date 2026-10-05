package io.github.coderirse.watchdog.data.api

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.Query
import retrofit2.http.Url

interface DeepSeekApi {
    @GET("/user/balance")
    suspend fun getBalance(
        @Header("Authorization") authorization: String
    ): Response<DeepSeekBalanceResponse>
}

interface KimiApi {
    @GET("/v1/users/me/balance")
    suspend fun getBalance(
        @Header("Authorization") authorization: String
    ): Response<KimiBalanceResponse>
}

interface GlmApi {
    @GET("/api/biz/tokenAccounts/list/my")
    suspend fun getTokenAccounts(
        @Header("Authorization") authorization: String
    ): Response<GlmTokenAccountsResponse>
}

/**
 * 智谱 Coding Plan 配额接口（cc-switch 等项目实测）：
 * - GET api/monitor/usage/quota/limit → data.{level, limits[{type, unit(3=5小时/6=周),
 *   percentage(已用百分比 0-100), nextResetTime(epoch ms)}]}
 * - 鉴权为 "Authorization: <APIKey>"（智谱特有：不加 Bearer 前缀）
 * - 余额端点 api/paas/v4/users/me/balance 鉴权方式无公开资料，实现后真机实测
 */
interface GlmCodingPlanApi {
    @GET("api/monitor/usage/quota/limit")
    suspend fun getQuotaLimit(
        @Header("Authorization") apiKey: String
    ): Response<ResponseBody>

    @GET("api/paas/v4/users/me/balance")
    suspend fun getBalance(
        @Header("Authorization") apiKey: String
    ): Response<ResponseBody>
}

interface SiliconFlowApi {
    @GET("/v1/user/info")
    suspend fun getUserInfo(
        @Header("Authorization") authorization: String
    ): Response<SiliconFlowUserResponse>

    @GET("/v1/dashboard/billing/usage")
    suspend fun getBillingUsage(
        @Header("Authorization") authorization: String
    ): Response<SiliconFlowBillingUsageResponse>
}

/**
 * Kimi Code 订阅配额接口。
 * 注意：必须使用相对路径 "v1/usages"，否则 Retrofit 会丢弃 baseUrl 中的 "/coding/" 前缀。
 */
interface KimiCodeApi {
    @GET("v1/usages")
    suspend fun getUsages(
        @Header("Authorization") authorization: String
    ): Response<KimiCodeUsagesResponse>
}

/**
 * Kimi 控制台 SSR 页面抓取（方案乙：控制台数据由 Next.js 服务端渲染，无浏览器 XHR 接口，
 * 数据内嵌在页面 HTML 的 __NEXT_DATA__/self.__next_f 中）。带登录 Cookie 请求首页 HTML，
 * 由 KimiConsoleParser 从 HTML 内联 JSON 解析余额/消费。
 */
interface KimiConsoleApi {
    @Headers(
        "Accept: text/html,application/xhtml+xml,*/*",
        "Accept-Language: zh-CN,zh;q=0.9,en;q=0.8",
        "User-Agent: Mozilla/5.0 (Linux; Android 15; Pixel) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    )
    @GET("/")
    suspend fun getConsoleHome(
        @Header("Cookie") cookie: String
    ): Response<ResponseBody>
}

/**
 * 小米 MiMo 网页控制台内部接口（非官方，可能变更）。
 *
 * 官方文档确认：MiMo 没有"仅凭 API Key"的余额/用量查询接口；
 * 网页控制台（platform.xiaomimimo.com）通过同源 /api/v1 路径接口获取数据。
 *
 * ⚠️ 鉴权必须是**整串浏览器 Cookie**，不能只带 api-platform_ph：
 * 2026-09 真机实测（登录刚完成、页面内 fetch 校验通过的前提下）——
 * 只带 `api-platform_ph` 请求头时 detail/usage 稳定返回
 * `401 {"code":401,"loginUrl":"https://account.xiaomi.com/pass/serviceLogin..."}`
 * 且响应头含 `www-authenticate`（标准鉴权拒绝，非风控）。
 * 同一时刻浏览器同源请求会带上全部 4 个 Cookie
 * （api-platform_serviceToken / userId / api-platform_slh / api-platform_ph），
 * 网关即正常返回。故 [MiMoConsoleApi] 的每个方法都要求传完整 Cookie 串。
 *
 * 另注：api-platform_ph 仍作为独立请求头保留传入（部分部署可能同时校验），
 * 但仅当它存在时才发送。
 *
 * 所有响应以原始 JSON 返回，由 [MiMoConsoleParser] 做防御性解析。
 * 请求携带浏览器特征头（同 WebView UA）降低网关风控误伤。
 */
interface MiMoConsoleApi {
    @Headers(
        "Accept: application/json, text/plain, */*",
        "Accept-Language: zh-CN,zh;q=0.9,en;q=0.8",
        "Origin: https://platform.xiaomimimo.com",
        "Referer: https://platform.xiaomimimo.com/",
        // 与 WebLoginActivity 的 WebView UA 保持一致
        "User-Agent: Mozilla/5.0 (Linux; Android 15; Pixel) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    )
    @GET("api/v1/tokenPlan/detail")
    suspend fun getTokenPlanDetail(
        @Header("api-platform_ph") session: String?,
        @Header("Cookie") cookie: String?
    ): Response<ResponseBody>

    @Headers(
        "Accept: application/json, text/plain, */*",
        "Accept-Language: zh-CN,zh;q=0.9,en;q=0.8",
        "Origin: https://platform.xiaomimimo.com",
        "Referer: https://platform.xiaomimimo.com/",
        "User-Agent: Mozilla/5.0 (Linux; Android 15; Pixel) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    )
    @GET("api/v1/tokenPlan/usage")
    suspend fun getTokenPlanUsage(
        @Header("api-platform_ph") session: String?,
        @Header("Cookie") cookie: String?
    ): Response<ResponseBody>

    @Headers(
        "Accept: application/json, text/plain, */*",
        "Accept-Language: zh-CN,zh;q=0.9,en;q=0.8",
        "Origin: https://platform.xiaomimimo.com",
        "Referer: https://platform.xiaomimimo.com/",
        "User-Agent: Mozilla/5.0 (Linux; Android 15; Pixel) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    )
    @GET("api/v1/tokenPlan/list")
    suspend fun getTokenPlanList(
        @Header("api-platform_ph") session: String?,
        @Header("Cookie") cookie: String?
    ): Response<ResponseBody>

    /** 通用 GET：用于带会话探测余额类候选路径（见 MiMoConsoleParser）。 */
    @Headers(
        "Accept: application/json, text/plain, */*",
        "Accept-Language: zh-CN,zh;q=0.9,en;q=0.8",
        "Origin: https://platform.xiaomimimo.com",
        "Referer: https://platform.xiaomimimo.com/",
        "User-Agent: Mozilla/5.0 (Linux; Android 15; Pixel) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    )
    @GET
    suspend fun getRaw(
        @Header("api-platform_ph") session: String?,
        @Header("Cookie") cookie: String?,
        @Url path: String
    ): Response<ResponseBody>
}

/**
 * DeepSeek 网页控制台内部接口（非官方，可能变更）。
 *
 * ⚠️ 外层是华为云 WAF：请求必须携带浏览器特征头（User-Agent/Accept/Origin/Referer），
 * 否则被 429 拦截（已实测）；建议同时带上登录时的 Cookie（WAF 指纹关联），
 * Cookie 由调用方从 WebSessionStore 读取后经 [okhttp3.Headers] 参数传入。
 *
 * 2026-02 对 platform.deepseek.com 前端 bundle 逆向确认：
 * - 鉴权头 Authorization: Bearer <userToken>，值为浏览器 localStorage 中 userToken
 *   （注意是 JSON 包装结构 {"value": "..."}，WebLoginActivity 抓取时已解包）；
 * - 用量的时间参数均为 epoch 秒，tz 为时区偏移秒数（如 UTC+8 = 28800）；
 * - 响应统一为 {data: {biz_data: {...}}} 结构。
 *
 * 所有响应以原始 JSON 返回，由 DeepSeekConsoleParser 做防御性解析。
 */
interface DeepSeekConsoleApi {

    /** 用户汇总：余额钱包（normal_wallets）、真实本月用量（monthly_usage）、累计用量（total_usage）。 */
    @Headers(
        "Accept: application/json, text/plain, */*",
        "Accept-Language: zh-CN,zh;q=0.9,en;q=0.8",
        "Origin: https://platform.deepseek.com",
        "Referer: https://platform.deepseek.com/",
        "User-Agent: Mozilla/5.0 (Linux; Android 15; Pixel) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    )
    @GET("api/v0/users/get_user_summary")
    suspend fun getUserSummary(
        @Header("Authorization") authorization: String,
        @Header("Cookie") cookie: String?
    ): Response<ResponseBody>

    /**
     * 按模型/日期的月度花费（GET api/v0/usage/cost?month=&year=，实测可用）。
     * 响应 biz_data.total[] 为 {model, usage:[{type, amount}]}，amount 为 CNY 金额。
     * 注意：旧版 by_api_key/cost 端点的 start/end/tz 参数已返回 INVALID_PARAM（2026-08 实测）。
     */
    @Headers(
        "Accept: application/json, text/plain, */*",
        "Accept-Language: zh-CN,zh;q=0.9,en;q=0.8",
        "Origin: https://platform.deepseek.com",
        "Referer: https://platform.deepseek.com/",
        "User-Agent: Mozilla/5.0 (Linux; Android 15; Pixel) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    )
    @GET("api/v0/usage/cost")
    suspend fun getUsageCostMonthly(
        @Header("Authorization") authorization: String,
        @Header("Cookie") cookie: String?,
        @Query("month") month: Int,
        @Query("year") year: Int
    ): Response<ResponseBody>

    /**
     * 按模型/日期的月度 Token 用量（GET api/v0/usage/amount?month=&year=，实测可用）。
     * 响应 biz_data.total[] 为 {model, usage:[{type, amount}]}，amount 为 token/请求数。
     */
    @Headers(
        "Accept: application/json, text/plain, */*",
        "Accept-Language: zh-CN,zh;q=0.9,en;q=0.8",
        "Origin: https://platform.deepseek.com",
        "Referer: https://platform.deepseek.com/",
        "User-Agent: Mozilla/5.0 (Linux; Android 15; Pixel) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    )
    @GET("api/v0/usage/amount")
    suspend fun getUsageAmountMonthly(
        @Header("Authorization") authorization: String,
        @Header("Cookie") cookie: String?,
        @Query("month") month: Int,
        @Query("year") year: Int
    ): Response<ResponseBody>
}
