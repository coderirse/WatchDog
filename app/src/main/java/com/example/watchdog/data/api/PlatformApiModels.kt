package com.example.watchdog.data.api

import com.google.gson.annotations.SerializedName
import com.google.gson.JsonElement

// ===== DeepSeek 余额 =====

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
    @SerializedName("tokensMagnitude") val tokensMagnitude: Double?,
    @SerializedName("status") val status: String?,
    @SerializedName("suitableModel") val suitableModel: String?,
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

// ===== Kimi Code (api.kimi.com/coding) — 订阅配额 =====
//
// 注意：以下响应结构【未完整实测】。2026-08 验证记录：
// - 无效 Key 返回 401 {"code":"unauthenticated",...}（端点存在）；
// - 真机实测确认顶层配额数组字段名为 "limits"，且 limits[i].window 为【对象】。
// 其余字段名仍为合理猜测，接口可能变更。
// 因此全部字段可空、字段名为合理猜测并给出 @SerializedName alternate 备选；
// 类型不确定的字段（数值可能是数字或字符串、时间可能是时间戳或 ISO 字符串、
// plan/booster 可能是字符串或对象）一律声明为 JsonElement，由 Repository 层兜底解析，
// 保证任何字段缺失或类型不符都不会导致 Gson 反序列化崩溃。

/**
 * Kimi Code GET v1/usages 响应（未实测，接口可能变更）。
 */
data class KimiCodeUsagesResponse(
    // 订阅套餐名：可能是字符串（"Pro"）或对象（{"name": ...}）
    @SerializedName(value = "plan", alternate = ["planName", "plan_name", "subscription", "subscriptionName", "tier"])
    val plan: JsonElement? = null,

    // 配额窗口列表（5小时 / 周 / 月等）
    @SerializedName(value = "windows", alternate = ["quotas", "quotaWindows", "limits", "usages", "rateLimits", "rate_limits"])
    val windows: List<KimiCodeQuotaWindow>? = null,

    // booster 信息：类型未知，可能是字符串、对象或数组
    @SerializedName(value = "booster", alternate = ["boosterInfo", "boost", "boosters"])
    val booster: JsonElement? = null,

    // 订阅到期时间
    @SerializedName(value = "expiresAt", alternate = ["expires_at", "expireTime", "expire_time", "subscriptionExpiresAt", "endTime", "end_time"])
    val expiresAt: JsonElement? = null,

    // 顶层配额重置时间
    @SerializedName(value = "resetTime", alternate = ["reset_time", "resetAt", "reset_at"])
    val resetTime: JsonElement? = null
)

/**
 * 单个配额窗口（未实测，接口可能变更）。数值/时间字段均用 JsonElement 兜底。
 */
data class KimiCodeQuotaWindow(
    // 窗口名：2026-08 真机实测发现 limits[0].window 是【对象】而非字符串，
    // 声明 String 会导致 Gson 整体反序列化失败，故用 JsonElement 兜底
    @SerializedName(value = "name", alternate = ["window", "type", "label", "period", "title"])
    val name: JsonElement? = null,
    @SerializedName(value = "used", alternate = ["usedQuota", "usedAmount", "consumed", "usage"])
    val used: JsonElement? = null,
    @SerializedName(value = "remaining", alternate = ["remain", "left", "available"])
    val remaining: JsonElement? = null,
    @SerializedName(value = "limit", alternate = ["total", "quota", "cap", "maximum", "max"])
    val limit: JsonElement? = null,
    @SerializedName(value = "resetTime", alternate = ["reset_time", "resetAt", "resetsAt", "resets_at"])
    val resetTime: JsonElement? = null,
    @SerializedName(value = "expiresAt", alternate = ["expires_at", "expireTime", "endTime"])
    val expiresAt: JsonElement? = null
)
