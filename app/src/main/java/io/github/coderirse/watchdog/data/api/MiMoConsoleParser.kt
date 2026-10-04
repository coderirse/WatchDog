package io.github.coderirse.watchdog.data.api

import io.github.coderirse.watchdog.data.model.QuotaWindow
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * 小米 MiMo 网页控制台（platform.xiaomimimo.com 的 /api/v1 路径）响应的防御性解析。
 *
 * 该系列接口均【未完整实测】（需要真实登录会话），响应结构可能随控制台版本变更，
 * 且会话过期时会被重定向到登录页/返回 401。因此所有解析均：
 * - 字段名采用候选键列表匹配（名称/数值/时间多种写法兼容）；
 * - 任何字段缺失或类型不符都返回 null / 跳过，绝不抛异常到 UI。
 * 共享 JSON 工具见 [JsonExt]。全部为纯函数，独立成对象以便单元测试。
 */
object MiMoConsoleParser {

    /**
     * 余额类候选路径。运行时按顺序探测（带真实会话），
     * 第一个返回 200 且能解析出余额字段的路径胜出（命中路径会被持久化，见 SettingsStore.saveProbePath）。
     * 说明：MiMo 网关对未知路径无会话时统一回 401，无法离线预判；
     * 带会话后不存在路径将回 404/HTML，可被 [parseBalanceInfo] 过滤。
     */
    val BALANCE_CANDIDATES = listOf(
        // 实测路径置顶：网页控制台实际调用的余额接口，避免先撞未实测端点拿到歧义数值
        "/api/v1/balance",
        "/api/v1/account/balance",
        "/api/v1/user/balance",
        "/api/v1/wallet/balance",
        "/api/v1/accountBalance",
        "/api/v1/cashBalance",
        "/api/v1/finance/balance",
        "/api/v1/credit/balance"
    )

    /** Token Plan 订阅详情（计划名/额度/到期）。 */
    data class TokenPlanDetail(
        val planName: String?,
        val totalCredits: Double?,
        val usedCredits: Double?,
        val expiresAt: Long?,
        val cycleLabel: String?
    )

    /** 探测到的余额信息（来源可能为任一候选路径）。 */
    data class BalanceInfo(
        val balance: Double,
        val currency: String?,
        val frozenBalance: Double?,
        val giftBalance: Double?
    )

    // ===== tokenPlan/detail =====

    private val planNameKeys = listOf("planName", "plan_name", "packageName", "package_name", "name", "tier", "title")
    private val totalKeys = listOf("totalCredits", "total_credits", "creditsQuota", "credits_quota", "credits", "total", "quota", "limit", "maxCredits", "max_credits")
    private val usedKeys = listOf("usedCredits", "used_credits", "usedQuota", "used_quota", "consumedCredits", "consumed_credits", "used", "consumed")
    private val expiresKeys = listOf("expiresAt", "expires_at", "expireTime", "expire_time", "validUntil", "valid_until", "endTime", "end_time", "subscriptionEndTime")
    private val cycleKeys = listOf("cycle", "cycleLabel", "cycle_label", "billingCycle", "billing_cycle", "subscriptionType", "subscription_type")

    private fun extractPlan(obj: JsonObject): TokenPlanDetail = TokenPlanDetail(
        planName = JsonExt.findString(obj, planNameKeys),
        totalCredits = JsonExt.findNumber(obj, totalKeys),
        usedCredits = JsonExt.findNumber(obj, usedKeys),
        expiresAt = JsonExt.findTime(obj, expiresKeys),
        cycleLabel = JsonExt.findString(obj, cycleKeys)
    )

    fun parseTokenPlanDetail(text: String?): TokenPlanDetail? {
        val root = JsonExt.parseJson(text) ?: return null
        // 响应可能是 {data: {...}} / {result: {...}} 包裹，先解一层
        val obj = JsonExt.unwrapObject(root) ?: return null

        // 顶层可能取不到：详情页存在嵌套对象（detail / subscription / plan 等），再下探一层
        if (JsonExt.findString(obj, planNameKeys) == null &&
            JsonExt.findNumber(obj, totalKeys) == null &&
            JsonExt.findTime(obj, expiresKeys) == null
        ) {
            val nested = firstObjectOf(obj, listOf("detail", "subscription", "plan", "tokenPlan", "data"))
                ?: return null
            return extractPlan(nested)
        }
        return extractPlan(obj)
    }

    // ===== tokenPlan/usage =====

    /**
     * 解析用量为配额窗口列表（QuotaWindow 与 Kimi Code 同构，UI 复用订阅模式展示）。
     * 支持数组（多个窗口）或单个对象（视为一个窗口，名称取"套餐"）。
     * 解析不到任何窗口时返回空列表（上层据此判断接口结构是否已变更）。
     */
    fun parseTokenPlanUsage(text: String?): List<QuotaWindow> {
        val root = JsonExt.parseJson(text) ?: return emptyList()
        val obj = JsonExt.unwrapObject(root) ?: return emptyList()

        // 数组优先：还可能是 {windows: [...], quotas: [...], list: [...]} 包裹
        val array = firstArrayOf(obj, listOf("windows", "quotas", "quotaWindows", "quota_windows", "usage", "usages", "list", "details", "records"))
        if (array != null) {
            val windows = array.mapNotNull { el ->
                if (el.isJsonObject) parseWindowObject(el.asJsonObject, fallbackName = null) else null
            }
            if (windows.isNotEmpty()) return windows
        }

        // 兜底：对象本身即单窗口（可能含 used/limit 字段）
        return listOfNotNull(parseWindowObject(obj, fallbackName = "套餐"))
    }

    private fun parseWindowObject(o: JsonObject, fallbackName: String?): QuotaWindow? {
        val name = JsonExt.findString(o, listOf("name", "type", "label", "period", "title", "window", "cycle"))
            ?: fallbackName
        val used = JsonExt.findNumber(o, listOf("used", "usedCredits", "used_credits", "usedQuota", "used_quota", "consumed", "consumedCredits", "consumed_credits"))
        val limit = JsonExt.findNumber(o, listOf("limit", "total", "totalCredits", "total_credits", "quota", "cap", "maximum", "max", "credits"))
        val remaining = JsonExt.findNumber(o, listOf("remaining", "remain", "left", "available", "availableCredits", "available_credits"))
            ?: if (used != null && limit != null) (limit - used).coerceAtLeast(0.0) else null
        // 必须识别到至少一个数值字段才算有效窗口，否则 {"code":401} 这类无数据对象会被误判
        if (used == null && limit == null && remaining == null) return null

        return QuotaWindow(
            name = name,
            used = used,
            remaining = remaining,
            limit = limit,
            resetTime = JsonExt.findTime(o, listOf("resetTime", "reset_time", "resetAt", "reset_at", "cycleStart", "cycle_start")),
            expiresAt = JsonExt.findTime(o, listOf("expiresAt", "expires_at", "expireTime", "expire_time", "endTime", "end_time", "validUntil", "valid_until"))
        )
    }

    // ===== 余额探测 =====

    /**
     * 从探测响应中解析余额；无法识别（HTML 登录页 / 404 页 / 字段缺失）返回 null。
     * 兼容常见字段：balance / cashBalance / totalBalance / availableBalance。
     * 不含裸 "amount"/"credits" 等宽泛键：未实测端点（如 Credits 类）的此类字段
     * 会被误当人民币余额（货币真伪由 MiMoProvider 按命中路径与显式货币字段判定）。
     */
    fun parseBalanceInfo(text: String?): BalanceInfo? {
        val root = JsonExt.parseJson(text) ?: return null
        val obj = JsonExt.unwrapObject(root) ?: return null

        val balance = JsonExt.findNumber(obj, listOf(
            "balance", "cashBalance", "cash_balance", "totalBalance", "total_balance",
            "availableBalance", "available_balance"
        )) ?: return null
        // 余额可能以"分"为单位（金额类平台常见）；但无实证，不做换算，保持原值。

        return BalanceInfo(
            balance = balance,
            currency = JsonExt.findString(obj, listOf("currency", "currencyType", "currency_type", "unit"))?.uppercase()?.takeIf { it in setOf("CNY", "USD") }
                ?: JsonExt.findString(obj, listOf("currency", "currencyType", "unit")),
            frozenBalance = JsonExt.findNumber(obj, listOf("frozenBalance", "frozen_balance", "freeze", "locked")),
            giftBalance = JsonExt.findNumber(obj, listOf("giftBalance", "gift_balance", "bonusBalance", "bonus_balance", "grantBalance"))
        )
    }

    // ===== 局部结构探查工具 =====

    private fun firstArrayOf(o: JsonObject, keys: List<String>): List<JsonElement>? {
        for (key in keys) {
            val el = o.get(key) ?: continue
            if (el.isJsonArray) return el.asJsonArray.toList()
            if (el.isJsonObject) {
                // 数组可能被 {list: [...]} 再包裹一层
                val inner = el.asJsonObject.get("list")
                if (inner != null && inner.isJsonArray) return inner.asJsonArray.toList()
            }
        }
        return null
    }

    private fun firstObjectOf(o: JsonObject, keys: List<String>): JsonObject? {
        for (key in keys) {
            val el = o.get(key) ?: continue
            if (el.isJsonObject) return el.asJsonObject
        }
        return null
    }
}
