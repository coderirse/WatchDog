package com.example.watchdog.data.api

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
 * 小米 MiMo 网页控制台内部接口（非官方，可能变更）。
 *
 * 官方文档确认：MiMo 没有"仅凭 API Key"的余额/用量查询接口；
 * 网页控制台（platform.xiaomimimo.com）通过同源 /api/v1 路径接口获取数据，
 * 鉴权为名为 api-platform_ph 的请求头（值为浏览器 Cookie 中的同名值，
 * 由 WebLoginActivity 在用户完成网页登录后自动抓取）。
 *
 * 2026-02 bundle 逆向实测：
 * - 任意 /api/v1 路径在缺少会话头时统一返回 401（网关先鉴权后路由），
 *   因此无法离线预判余额接口路径，需带会话运行时探测（见 MiMoConsoleParser.BALANCE_CANDIDATES）。
 * - 已验证存在的路径：tokenPlan/detail、tokenPlan/usage、tokenPlan/list、balanceAlertConfig 等。
 *
 * 所有响应以原始 JSON 返回，由 MiMoConsoleParser 做防御性解析。
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
        @Header("api-platform_ph") session: String
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
        @Header("api-platform_ph") session: String
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
        @Header("api-platform_ph") session: String
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
        @Header("api-platform_ph") session: String,
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
