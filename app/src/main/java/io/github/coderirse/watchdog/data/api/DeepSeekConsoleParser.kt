package io.github.coderirse.watchdog.data.api

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.github.coderirse.watchdog.data.api.JsonExt.arrayOrNull
import io.github.coderirse.watchdog.data.api.JsonExt.strOrNull

/**
 * DeepSeek 网页控制台（platform.deepseek.com 的 /api/v0 路径）响应的防御性解析。
 *
 * 接口结构来自 2026-02 对前端 bundle 的逆向：
 * - 响应统一为 {data: {biz_data: {...}}}；
 * - user_summary: biz_data.normal_wallets[{balance, currency, token_estimation}]、
 *   monthly_usage（真实本月用量）、total_usage（累计用量）、current_token；
 * - 月度用量端点 usage/cost|amount?month=&year=（2026-08 实测可用）：
 *   biz_data.total[]（按模型）与 biz_data.days[].data[]（按天×模型），
 *   usage 项为 {type, amount}，cost 端点 amount 为 CNY 金额、amount 端点为 token/请求数。
 *
 * 与 KimiCodeParser 一致：字段缺失/类型不符一律返回 null/跳过，绝不抛异常到 UI。
 * 全部为纯函数，独立成对象以便单元测试。
 *
 * 注：旧版 by_api_key/cost|amount 端点的 start/end/tz 参数已失效（INVALID_PARAM），
 * 对应解析函数已随主流程一并移除。
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

    // ===== users/get_user_summary =====

    fun parseUserSummary(text: String?): UserSummary? {
        val biz = unwrapBizData(text) ?: return null

        val wallets = listOf("normal_wallets", "wallets", "balance_wallets")
            .firstNotNullOfOrNull { key -> biz.arrayOrNull(key) }
            ?.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val o = el.asJsonObject
                val balance = JsonExt.findNumber(o, listOf("balance", "total_balance", "amount")) ?: return@mapNotNull null
                Wallet(
                    balance = balance,
                    currency = JsonExt.findString(o, listOf("currency", "currency_type"))?.uppercase(),
                    tokenEstimation = JsonExt.findNumber(o, listOf("token_estimation", "tokenEstimation"))?.toLong()
                )
            } ?: emptyList()

        val monthly = JsonExt.findNumber(biz, listOf("monthly_usage", "monthlyUsage", "month_usage", "month_usage_amount"))
        val total = JsonExt.findNumber(biz, listOf("total_usage", "totalUsage", "total_usage_amount"))
        val currentToken = JsonExt.findNumber(biz, listOf("current_token", "currentToken"))?.toLong()

        if (wallets.isEmpty() && monthly == null && total == null) return null
        return UserSummary(wallets, monthly, total, currentToken)
    }

    /** 解 {data: {biz_data: {...}}}（缺失层时退化向上查找）。 */
    private fun unwrapBizData(text: String?): JsonObject? {
        val root = JsonExt.parseJson(text)?.takeIf { it.isJsonObject } ?: return null
        val obj = root.asJsonObject

        for (key in listOf("data", "result", "resData")) {
            val data = obj.get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            data.get("biz_data")?.takeIf { it.isJsonObject }?.asJsonObject?.let { return it }
        }
        // 退化：根级或 data 级直接是 biz_data 字段
        if (obj.has("biz_data") && obj.get("biz_data").isJsonObject) return obj.get("biz_data").asJsonObject
        val data = obj.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
        if (data != null && data.has("biz_data") && data.get("biz_data").isJsonObject) {
            return data.get("biz_data").asJsonObject
        }
        return obj
    }

    // ===== 月度用量端点（GET api/v0/usage/cost|amount?month=&year=）=====

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
        val data = unwrapDataNode(text) ?: return null
        val bizEl = data.get("biz_data") ?: return null
        val total: List<JsonElement> = when {
            bizEl.isJsonArray -> bizEl.asJsonArray
                .mapNotNull { (it as? JsonObject)?.arrayOrNull("total") }
                .flatten()
            bizEl.isJsonObject -> bizEl.asJsonObject.arrayOrNull("total") ?: return null
            else -> return null
        }
        if (total.isEmpty()) return null

        val rows = total.mapNotNull { el ->
            val row = el as? JsonObject ?: return@mapNotNull null
            val model = row.strOrNull("model") ?: return@mapNotNull null
            accumulateUsage(MonthlyRow(model), row, costMode)
        }
        // biz_data 数组按账户/币种分组时同名模型会出现多行，按模型聚合求和
        return mergeMonthlyRows(rows).ifEmpty { null }
    }

    /** 把一行 usage 数组（[{type, amount}]）累加进 MonthlyRow。 */
    private fun accumulateUsage(r0: MonthlyRow, row: JsonObject, costMode: Boolean): MonthlyRow {
        var r = r0
        val usage = row.get("usage")?.takeIf { it.isJsonArray }?.asJsonArray ?: return r
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
        return r
    }

    private fun mergeMonthlyRows(rows: List<MonthlyRow>): List<MonthlyRow> =
        rows.groupBy { it.model }.map { (_, rs) ->
            rs.reduce { a, b ->
                MonthlyRow(
                    model = a.model,
                    requests = a.requests + b.requests,
                    promptTokens = a.promptTokens + b.promptTokens,
                    cacheHit = a.cacheHit + b.cacheHit,
                    cacheMiss = a.cacheMiss + b.cacheMiss,
                    outputTokens = a.outputTokens + b.outputTokens,
                    cost = a.cost + b.cost
                )
            }
        }

    // ===== 按天（daily）解析：月度接口 biz_data[].days[]，含每日各指标 =====

    /** 某一天的各 token/成本指标。cost 端点（[costMode]=true）下由 cost 字段填金额。 */
    data class DailyRow(
        val date: String,
        val requests: Long = 0,
        val inputTokens: Long = 0,
        val cacheHit: Long = 0,
        val cacheMiss: Long = 0,
        val outputTokens: Long = 0,
        val cost: Double = 0.0
    ) {
        val totalTokens: Long get() = inputTokens + outputTokens
    }

    /**
     * 解析月度接口 biz_data[].days[]（按天，全模型聚合）。每项结构（真机实测）：
     * {date: "2026-08-23", data: [ { model, usage: [ {type, amount} ] }, ... ]}
     * 兼容对象形态 biz_data.days。
     *
     * 输入语义：真机实测 PROMPT_TOKEN 恒为 0，真实输入量在 PROMPT_CACHE_HIT_TOKEN
     * 与 PROMPT_CACHE_MISS_TOKEN，故 [DailyRow.inputTokens] = prompt + hit + miss。
     */
    fun parseMonthlyDays(text: String?, costMode: Boolean): List<DailyRow>? {
        val days = extractDays(text) ?: return null

        val rows = days.mapNotNull { el ->
            val d = el as? JsonObject ?: return@mapNotNull null
            val date = d.strOrNull("date") ?: return@mapNotNull null
            var r = DailyRow(date)
            // days 项内 usage 有两种形态：直接 usage[]（部分端点）或 data[].usage[]
            val usages = buildList {
                d.arrayOrNull("usage")?.let { addAll(it) }
                d.arrayOrNull("data")?.forEach { item ->
                    (item as? JsonObject)?.arrayOrNull("usage")?.let { addAll(it) }
                }
            }
            for (u in usages) {
                val uo = u as? JsonObject ?: continue
                val type = uo.strOrNull("type") ?: continue
                val amount = uo.strOrNull("amount")?.toDoubleOrNull()
                    ?: (uo.get("amount")?.takeIf { it.isJsonPrimitive }?.asDouble) ?: continue
                r = when (type) {
                    "REQUEST" -> r.copy(requests = r.requests + amount.toLong())
                    "PROMPT_TOKEN" -> if (costMode) r.copy(cost = r.cost + amount)
                        else r.copy(inputTokens = r.inputTokens + amount.toLong())
                    "PROMPT_CACHE_HIT_TOKEN" -> if (costMode) r.copy(cost = r.cost + amount)
                        else r.copy(cacheHit = r.cacheHit + amount.toLong())
                    "PROMPT_CACHE_MISS_TOKEN" -> if (costMode) r.copy(cost = r.cost + amount)
                        else r.copy(cacheMiss = r.cacheMiss + amount.toLong())
                    "RESPONSE_TOKEN" -> if (costMode) r.copy(cost = r.cost + amount)
                        else r.copy(outputTokens = r.outputTokens + amount.toLong())
                    else -> r
                }
            }
            // 归一化：输入 = 未缓存 + 缓存命中 + 缓存未命中（costMode 下三者均为 0）
            if (!costMode) r = r.copy(inputTokens = r.inputTokens + r.cacheHit + r.cacheMiss)
            r
        }
        return rows.ifEmpty { null }
    }

    // ===== 按模型 × 按天（daily-by-model）解析：月度接口 biz_data[].days[].data[].model =====

    /**
     * 某一天某模型的各 token/成本指标。输入 = prompt + hit + miss（真实输入量）。
     * cost 端点（[costMode]=true）下 amount 为 CNY 金额，累入 [cost]。
     */
    data class DailyModelRow(
        val date: String,
        val model: String,
        val requests: Long = 0,
        val inputTokens: Long = 0,
        val cacheHit: Long = 0,
        val cacheMiss: Long = 0,
        val outputTokens: Long = 0,
        val cost: Double = 0.0
    ) {
        val totalTokens: Long get() = inputTokens + outputTokens
    }

    /**
     * 解析月度接口 biz_data[].days[].data[].model（按模型 × 按天）。
     * 真机实测（2026-08）：amount 端点 biz_data 为对象 {total, days}，days[].data[] 每项
     * {model, usage:[{type, amount}]}；cost 端点 biz_data 为数组（按币种分组），结构相同，
     * amount 为货币金额。
     */
    fun parseMonthlyDaysByModel(text: String?, costMode: Boolean): List<DailyModelRow>? {
        val days = extractDays(text) ?: return null

        val rows = days.mapNotNull { el ->
            val d = el as? JsonObject ?: return@mapNotNull null
            val date = d.strOrNull("date") ?: return@mapNotNull null
            // data[] 每项 {model, usage[]}（真机实测每项均带 model）
            val dataArr = d.arrayOrNull("data") ?: return@mapNotNull null
            val perModel = dataArr.mapNotNull { item ->
                val io = item as? JsonObject ?: return@mapNotNull null
                val model = io.strOrNull("model") ?: return@mapNotNull null
                val usage = io.get("usage")?.takeIf { it.isJsonArray }?.asJsonArray ?: return@mapNotNull null
                var r = DailyModelRow(date, model)
                for (u in usage) {
                    val uo = u as? JsonObject ?: continue
                    val type = uo.strOrNull("type") ?: continue
                    val amount = uo.strOrNull("amount")?.toDoubleOrNull()
                        ?: (uo.get("amount")?.takeIf { it.isJsonPrimitive }?.asDouble) ?: continue
                    r = when (type) {
                        "REQUEST" -> r.copy(requests = r.requests + amount.toLong())
                        "PROMPT_TOKEN" -> if (costMode) r.copy(cost = r.cost + amount)
                            else r.copy(inputTokens = r.inputTokens + amount.toLong())
                        "PROMPT_CACHE_HIT_TOKEN" -> if (costMode) r.copy(cost = r.cost + amount)
                            else r.copy(cacheHit = r.cacheHit + amount.toLong())
                        "PROMPT_CACHE_MISS_TOKEN" -> if (costMode) r.copy(cost = r.cost + amount)
                            else r.copy(cacheMiss = r.cacheMiss + amount.toLong())
                        "RESPONSE_TOKEN" -> if (costMode) r.copy(cost = r.cost + amount)
                            else r.copy(outputTokens = r.outputTokens + amount.toLong())
                        else -> r
                    }
                }
                // 归一化：输入 = prompt + hit + miss（costMode 下三者均为 0）
                if (!costMode) r = r.copy(inputTokens = r.inputTokens + r.cacheHit + r.cacheMiss)
                r
            }
            // 同日同模型（少见：多分组重复）聚合求和
            perModel.groupBy { it.model }.map { (_, rs) ->
                rs.reduce { a, b ->
                    DailyModelRow(
                        date = date,
                        model = a.model,
                        requests = a.requests + b.requests,
                        inputTokens = a.inputTokens + b.inputTokens,
                        cacheHit = a.cacheHit + b.cacheHit,
                        cacheMiss = a.cacheMiss + b.cacheMiss,
                        outputTokens = a.outputTokens + b.outputTokens,
                        cost = a.cost + b.cost
                    )
                }
            }
        }.flatten()

        // 跨 biz_data 分组（币种/账户）时同名模型同天再聚合一次
        val merged = rows.groupBy { it.date + "|" + it.model }.map { (_, rs) ->
            rs.reduce { a, b ->
                DailyModelRow(
                    date = a.date,
                    model = a.model,
                    requests = a.requests + b.requests,
                    inputTokens = a.inputTokens + b.inputTokens,
                    cacheHit = a.cacheHit + b.cacheHit,
                    cacheMiss = a.cacheMiss + b.cacheMiss,
                    outputTokens = a.outputTokens + b.outputTokens,
                    cost = a.cost + b.cost
                )
            }
        }
        return merged.sortedBy { it.date }.ifEmpty { null }
    }

    // ===== 共用：月度端点响应骨架校验 + days 提取 =====

    /** 校验 {data:{biz_code:0}} 骨架并返回 data 节点；异常返回 null。 */
    private fun unwrapDataNode(text: String?): JsonObject? {
        val root = JsonExt.parseJson(text)?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val data = root.get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val bizCode = data.get("biz_code")?.takeIf { it.isJsonPrimitive }
            ?.let { runCatching { it.asInt }.getOrNull() } ?: 0
        if (bizCode != 0) return null
        return data
    }

    /** 提取 biz_data[].days[]（兼容 biz_data 对象形态）。 */
    private fun extractDays(text: String?): List<JsonElement>? {
        val data = unwrapDataNode(text) ?: return null
        val bizEl = data.get("biz_data") ?: return null
        val days = when {
            bizEl.isJsonArray -> bizEl.asJsonArray
                .mapNotNull { (it as? JsonObject)?.arrayOrNull("days") }
                .flatten()
            bizEl.isJsonObject -> bizEl.asJsonObject.arrayOrNull("days") ?: return null
            else -> return null
        }
        return days.ifEmpty { null }
    }
}
