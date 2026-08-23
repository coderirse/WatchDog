package com.example.watchdog.data.api

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * DeepSeek 网页控制台（platform.deepseek.com 的 /api/v0 路径）响应的防御性解析。
 *
 * 接口结构来自 2026-02 对前端 bundle 的逆向：
 * - 响应统一为 {data: {biz_data: {...}}}；
 * - user_summary: biz_data.normal_wallets[{balance, currency, token_estimation}]、
 *   monthly_usage（真实本月用量）、total_usage（累计用量）、current_token；
 * - by_api_key/cost: biz_data.data[{currency, series[{api_key, model, buckets[{time, cost}]}]}]；
 * - by_api_key/amount: biz_data.series[{api_key, model, buckets[{time, usage:{PROMPT_CACHE_HIT_TOKEN,
 *   PROMPT_CACHE_MISS_TOKEN, RESPONSE_TOKEN, REQUEST}}]}]。
 *
 * 与 KimiCodeParser 一致：字段缺失/类型不符一律返回 null/跳过，绝不抛异常到 UI。
 * 全部为纯函数，独立成对象以便单元测试。
 */
object DeepSeekConsoleParser {

    data class Wallet(
        val balance: Double,
        val currency: String?,
        val tokenEstimation: Long?
    )

    data class UserSummary(
        val wallets: List<Wallet> = emptyList(),
        val monthlyUsage: Double? = null,
        val totalUsage: Double? = null,
        val currentToken: Long? = null
    ) {
        /** CNY 钱包余额（首选展示币种）；无 CNY 时取第一个钱包。 */
        val primaryBalance: Wallet?
            get() = wallets.firstOrNull { it.currency.equals("CNY", true) } ?: wallets.firstOrNull()
    }

    data class CostBucket(val timeSec: Long, val cost: Double)

    data class UsageBucket(
        val timeSec: Long,
        val promptCacheHit: Double?,
        val promptCacheMiss: Double?,
        val response: Double?,
        val request: Double?
    )

    data class SeriesRow<T>(
        val apiKey: String?,
        val model: String?,
        val buckets: List<T>
    )

    data class CostSeries(
        val currency: String?,
        val series: List<SeriesRow<CostBucket>>
    )

    fun parseJson(text: String?): JsonElement? = MiMoConsoleParser.parseJson(text)

    // ===== users/get_user_summary =====

    fun parseUserSummary(text: String?): UserSummary? {
        val biz = unwrapBizData(text) ?: return null

        val wallets = listOf("normal_wallets", "wallets", "balance_wallets")
            .firstNotNullOfOrNull { key -> asArray(biz, key) }
            ?.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val o = el.asJsonObject
                val balance = MiMoConsoleParser.findNumber(o, listOf("balance", "total_balance", "amount")) ?: return@mapNotNull null
                Wallet(
                    balance = balance,
                    currency = MiMoConsoleParser.findString(o, listOf("currency", "currency_type"))
                        ?.uppercase(),
                    tokenEstimation = MiMoConsoleParser.findNumber(o, listOf("token_estimation", "tokenEstimation"))
                        ?.toLong()
                )
            } ?: emptyList()

        val monthly = MiMoConsoleParser.findNumber(biz, listOf("monthly_usage", "monthlyUsage", "month_usage", "month_usage_amount"))
        val total = MiMoConsoleParser.findNumber(biz, listOf("total_usage", "totalUsage", "total_usage_amount"))
        val currentToken = MiMoConsoleParser.findNumber(biz, listOf("current_token", "currentToken"))
            ?.toLong()

        if (wallets.isEmpty() && monthly == null && total == null) return null
        return UserSummary(wallets, monthly, total, currentToken)
    }

    // ===== usage/by_api_key/cost =====

    fun parseUsageCost(text: String?): List<CostSeries>? {
        val biz = unwrapBizData(text) ?: return null
        val dataArr = asArray(biz, listOf("data", "costs", "list")) ?: return null

        val result = dataArr.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val o = el.asJsonObject
            val currency = MiMoConsoleParser.findString(o, listOf("currency", "currency_type"))?.uppercase()
            val series = asArray(o, listOf("series", "items", "records"))
                ?.mapNotNull { s ->
                    if (!s.isJsonObject) return@mapNotNull null
                    val so = s.asJsonObject
                    val buckets = asArray(so, listOf("buckets", "daily", "points"))
                        ?.mapNotNull { b -> parseCostBucket(b) } ?: return@mapNotNull null
                    SeriesRow(
                        apiKey = MiMoConsoleParser.findString(so, listOf("api_key", "apiKey", "key")),
                        model = MiMoConsoleParser.findString(so, listOf("model", "model_name", "modelName")),
                        buckets = buckets
                    )
                } ?: return@mapNotNull null
            CostSeries(currency, series)
        }
        return result.takeIf { it.isNotEmpty() }
    }

    private fun parseCostBucket(el: JsonElement): CostBucket? {
        if (!el.isJsonObject) return null
        val o = el.asJsonObject
        val time = MiMoConsoleParser.findNumber(o, listOf("time", "date", "timestamp", "day")) ?: return null
        val cost = MiMoConsoleParser.findNumber(o, listOf("cost", "amount", "total_cost", "totalCost")) ?: return null
        return CostBucket(normalizeSec(time), cost)
    }

    // ===== usage/by_api_key/amount =====

    fun parseUsageAmount(text: String?): List<SeriesRow<UsageBucket>>? {
        val biz = unwrapBizData(text) ?: return null
        val seriesArr = asArray(biz, listOf("series", "data", "items", "records")) ?: return null

        val result = seriesArr.mapNotNull { s ->
            if (!s.isJsonObject) return@mapNotNull null
            val so = s.asJsonObject
            val buckets = asArray(so, listOf("buckets", "daily", "points"))
                ?.mapNotNull { b -> parseUsageBucket(b) } ?: return@mapNotNull null
            SeriesRow(
                apiKey = MiMoConsoleParser.findString(so, listOf("api_key", "apiKey", "key")),
                model = MiMoConsoleParser.findString(so, listOf("model", "model_name", "modelName")),
                buckets = buckets
            )
        }
        return result.takeIf { it.isNotEmpty() }
    }

    private fun parseUsageBucket(el: JsonElement): UsageBucket? {
        if (!el.isJsonObject) return null
        val o = el.asJsonObject
        val time = MiMoConsoleParser.findNumber(o, listOf("time", "date", "timestamp", "day")) ?: return null
        // usage 可能平铺在 bucket 本身，也可能包裹在 usage 对象里
        val usage = when (val u = o.get("usage")) {
            null -> o
            else -> if (u.isJsonObject) u.asJsonObject else o
        }
        val hit = MiMoConsoleParser.findNumber(usage, listOf("PROMPT_CACHE_HIT_TOKEN", "promptCacheHitToken", "prompt_cache_hit_token", "prompt_tokens_cache_hit"))
        val miss = MiMoConsoleParser.findNumber(usage, listOf("PROMPT_CACHE_MISS_TOKEN", "promptCacheMissToken", "prompt_cache_miss_token", "prompt_tokens_cache_miss"))
        val response = MiMoConsoleParser.findNumber(usage, listOf("RESPONSE_TOKEN", "responseToken", "response_token", "completion_tokens"))
        val request = MiMoConsoleParser.findNumber(usage, listOf("REQUEST", "request", "request_count", "calls"))
        // time 已由上方 ?: return null 保证非空，仅需确认识别到任一用量字段
        if (hit == null && miss == null && response == null && request == null) return null
        return UsageBucket(normalizeSec(time), hit, miss, response, request)
    }

    /** time 可能是秒级或毫秒级时间戳（>= 1e12 视为毫秒）。 */
    private fun normalizeSec(v: Double): Long {
        return if (v >= 1_000_000_000_000.0) (v / 1000).toLong() else v.toLong()
    }

    // ===== 通用工具 =====

    /** 解 {data: {biz_data: {...}}}（缺失层时退化向上查找）。 */
    private fun unwrapBizData(text: String?): JsonObject? {
        val root = parseJson(text)?.takeIf { it.isJsonObject } ?: return null
        val obj = root.asJsonObject

        var biz: JsonObject? = null
        for (key in listOf("data", "result", "resData")) {
            val data = obj.get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            biz = data.get("biz_data")?.takeIf { it.isJsonObject }?.asJsonObject
            if (biz != null) return biz
        }
        // 退化：根级或 data 级直接是 biz_data 字段（JsonObject 不是 Map，不能用 in 判断）
        if (obj.has("biz_data") && obj.get("biz_data").isJsonObject) return obj.get("biz_data").asJsonObject
        val data = obj.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
        if (data != null && data.has("biz_data") && data.get("biz_data").isJsonObject) {
            return data.get("biz_data").asJsonObject
        }
        return obj
    }

    private fun asArray(o: JsonObject, key: String): List<JsonElement>? {
        val el = o.get(key) ?: return null
        return if (el.isJsonArray) el.asJsonArray.toList() else null
    }

    private fun asArray(o: JsonObject, keys: List<String>): List<JsonElement>? {
        for (key in keys) {
            val el = asArray(o, key) ?: continue
            return el
        }
        return null
    }

    // ===== 月度用量端点（GET api/v0/usage/cost|amount?month=&year=，2026-08 实测可用；
    // 旧版 by_api_key 端点的 start/end/tz 参数已返回 INVALID_PARAM）=====

    /**
     * 月度接口按模型汇总行。amount 端点下各字段为 token/请求数；
     * cost 端点（[costMode]=true）下 usage 项的 amount 为 CNY 金额，累入 [cost]。
     */
    data class MonthlyRow(
        val model: String,
        val requests: Long = 0,
        val promptTokens: Long = 0,
        val cacheHit: Long = 0,
        val cacheMiss: Long = 0,
        val outputTokens: Long = 0,
        val cost: Double = 0.0
    )

    /**
     * 解析月度接口 biz_data.total[]（按模型）；biz_code 非零或结构异常返回 null。
     * 真机实测（2026-08）：biz_data 为**数组**（按账户/币种分组，每项含 total[]），
     * 兼容对象形态（biz_data.total）以防御结构变化。
     */
    fun parseMonthlyTotals(text: String?, costMode: Boolean): List<MonthlyRow>? {
        val root = parseJson(text)?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val data = root.get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val bizCode = data.get("biz_code")?.takeIf { it.isJsonPrimitive }
            ?.let { runCatching { it.asInt }.getOrNull() } ?: 0
        if (bizCode != 0) return null
        val bizEl = data.get("biz_data") ?: return null
        val total: List<JsonElement> = when {
            bizEl.isJsonArray -> bizEl.asJsonArray
                .mapNotNull { (it as? JsonObject)?.let { o -> asArray(o, "total") } }
                .flatten()
            bizEl.isJsonObject -> asArray(bizEl.asJsonObject, "total") ?: return null
            else -> return null
        }
        if (total.isEmpty()) return null

        val rows = total.mapNotNull { el ->
            val row = el as? JsonObject ?: return@mapNotNull null
            val model = row.strOrNull("model") ?: return@mapNotNull null
            var r = MonthlyRow(model)
            val usage = row.get("usage")?.takeIf { it.isJsonArray }?.asJsonArray ?: return@mapNotNull r
            for (u in usage) {
                val uo = u as? JsonObject ?: continue
                val type = uo.strOrNull("type") ?: continue
                val amount = uo.strOrNull("amount")?.toDoubleOrNull()
                    ?: (uo.get("amount")?.takeIf { it.isJsonPrimitive }?.asDouble) ?: continue
                r = when (type) {
                    "REQUEST" -> r.copy(requests = r.requests + amount.toLong())
                    "PROMPT_TOKEN" -> if (costMode) r.copy(cost = r.cost + amount)
                        else r.copy(promptTokens = r.promptTokens + amount.toLong())
                    "PROMPT_CACHE_HIT_TOKEN" -> if (costMode) r.copy(cost = r.cost + amount)
                        else r.copy(cacheHit = r.cacheHit + amount.toLong())
                    "PROMPT_CACHE_MISS_TOKEN" -> if (costMode) r.copy(cost = r.cost + amount)
                        else r.copy(cacheMiss = r.cacheMiss + amount.toLong())
                    "RESPONSE_TOKEN" -> if (costMode) r.copy(cost = r.cost + amount)
                        else r.copy(outputTokens = r.outputTokens + amount.toLong())
                    else -> r
                }
            }
            r
        }
        // biz_data 数组按账户/币种分组时同名模型会出现多行，按模型聚合求和
        val merged = rows.groupBy { it.model }.map { (model, rs) ->
            rs.reduce { a, b ->
                MonthlyRow(
                    model = model,
                    requests = a.requests + b.requests,
                    promptTokens = a.promptTokens + b.promptTokens,
                    cacheHit = a.cacheHit + b.cacheHit,
                    cacheMiss = a.cacheMiss + b.cacheMiss,
                    outputTokens = a.outputTokens + b.outputTokens,
                    cost = a.cost + b.cost
                )
            }
        }
        return merged.ifEmpty { null }
    }

    private fun JsonObject.strOrNull(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.takeIf { it.asJsonPrimitive.isString }?.asString
}
