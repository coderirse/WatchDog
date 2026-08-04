package com.example.watchdog.data.api

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header

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
