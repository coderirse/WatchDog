package com.example.watchdog.data.api

import com.google.gson.annotations.SerializedName

// ===== DeepSeek =====

data class DeepSeekBalanceResponse(
    @SerializedName("is_available") val isAvailable: Boolean,
    @SerializedName("balance_infos") val balanceInfos: List<DeepSeekBalanceInfo>?
)

data class DeepSeekBalanceInfo(
    val currency: String?,
    @SerializedName("total_balance") val totalBalance: String?,
    @SerializedName("granted_balance") val grantedBalance: String?,
    @SerializedName("topped_up_balance") val toppedUpBalance: String?
)

// ===== Kimi (Moonshot) =====

data class KimiBalanceResponse(
    val code: Int?,
    val data: KimiBalanceData?
)

data class KimiBalanceData(
    @SerializedName("available_balance") val availableBalance: Double?,
    @SerializedName("voucher_balance") val voucherBalance: Double?,
    @SerializedName("cash_balance") val cashBalance: Double?
)

// ===== GLM (智谱) — tokenAccounts 接口 =====

data class GlmTokenAccountsResponse(
    val total: Int?,
    val rows: List<GlmTokenAccount>?
)

data class GlmTokenAccount(
    val id: Long?,
    @SerializedName("tokenNo") val tokenNo: String?,
    @SerializedName("tokenBalance") val tokenBalance: Double?,
    @SerializedName("totalAmount") val totalAmount: Double?,
    @SerializedName("expirationTime") val expirationTime: String?,
    @SerializedName("resourcePackageName") val resourcePackageName: String?
)

// ===== SiliconFlow =====

data class SiliconFlowUserResponse(
    val code: Int?,
    val message: String?,
    val status: Boolean?,
    val data: SiliconFlowUserData?
)

data class SiliconFlowUserData(
    val id: String?,
    val name: String?,
    val email: String?,
    val image: String?,
    val isAdmin: Boolean?,
    val balance: String?,
    val status: String?,
    @SerializedName("chargeBalance") val chargeBalance: String?,
    @SerializedName("totalBalance") val totalBalance: String?
)

/**
 * SiliconFlow /v1/dashboard/billing/usage 响应
 * 返回当月用量数据
 */
data class SiliconFlowBillingUsageResponse(
    val code: Int?,
    val message: String?,
    val status: Boolean?,
    val data: SiliconFlowBillingData?
)

data class SiliconFlowBillingData(
    @SerializedName("total_tokens") val totalTokens: Long?,
    @SerializedName("total_cost") val totalCost: String?,
    @SerializedName("current_month_tokens") val currentMonthTokens: Long?,
    @SerializedName("current_month_cost") val currentMonthCost: String?
)
