package io.github.coderirse.watchdog.data.api

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * 智谱 GLM Coding Plan 配额接口（GET open.bigmodel.cn/api/monitor/usage/quota/limit）防御性解析。
 *
 * 响应结构（参考 cc-switch 项目实测，bigmodel.cn 与 z.ai 共用后端字段一致）：
 * - 成功：{success: true, data: {level: "套餐名", limits: [{type, unit, percentage, nextResetTime}]}}
 * - 鉴权为 "Authorization: <APIKey>"（智谱特有：不加 Bearer 前缀）
 * - limits[]：type=TOKENS_LIMIT；unit=3 → 5 小时滚动窗口，unit=6 → 每周窗口；
 *   percentage=已用百分比(0-100)；nextResetTime=重置时间(epoch ms)
 *
 * 与其他 Parser 一致：字段缺失/类型不符返回 null/空，绝不抛异常到 UI，纯函数便于单测。
 */
object GlmCodingPlanParser {

    data class QuotaWindow(
        /** 窗口名称：5小时 / 周 */
        val name: String,
        /** 已用百分比（0-100）；缺失为 null */
        val usedPercent: Double?,
        /** 重置时间（epoch ms）；缺失为 null */
        val nextResetTime: Long?
    )

    data class Result(
        /** 套餐名；缺失为 null */
        val planName: String?,
        val windows: List<QuotaWindow>,
        /** 业务是否成功（success==false 或 http 层面失败时此值为 false 且带 errorMessage） */
        val success: Boolean,
        val errorMessage: String?
    )

    fun parse(text: String?): Result {
        val root = JsonExt.parseJson(text)
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject ?: return fail("响应不是有效 JSON")
        // 业务级错误：success=false
        if (root.boolOr("success") == false) {
            val msg = root.strOr("msg") ?: "未知错误"
            return fail("API 错误：$msg")
        }
        val data = root.objOr("data") ?: return fail("响应缺少 data 字段")

        val level = data.strOr("level")
        val limits = data.get("limits")?.takeIf { it.isJsonArray }?.asJsonArray ?: emptyList()
        val windows = limits.mapNotNull { el ->
            val item = el as? JsonObject ?: return@mapNotNull null
            val type = item.strOr("type") ?: return@mapNotNull null
            // 大小写不敏感：上游可能把 TOKENS_LIMIT 改成小写或驼峰
            if (!(type.equals("TOKENS_LIMIT", true) || type.equals("CREDIT_LIMIT", true))) {
                return@mapNotNull null
            }
            val unit = item.intOr("unit")
            // unit 语义来自 cc-switch 实测（3=5小时滚动窗口，6=周）。未知/缺失不再静默丢弃：
            // 上游新增窗口类型时展示通用名称，窗口消失本身就是"接口已变更"的信号
            val name = when (unit) {
                3 -> "5小时"
                6 -> "周"
                null -> "配额窗口"
                else -> "配额窗口(unit=$unit)"
            }
            val usedPercent = item.doubleOr("percentage")
            val reset = item.longOr("nextResetTime")
            QuotaWindow(name, usedPercent, reset)
        }

        if (level == null && windows.isEmpty()) {
            return fail("响应结构无法识别")
        }

        return Result(
            planName = level,
            windows = windows,
            success = true,
            errorMessage = null
        )
    }

    private fun fail(msg: String): Result = Result(null, emptyList(), false, msg)

    private fun JsonObject.strOr(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.boolOr(name: String): Boolean? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    private fun JsonObject.intOr(name: String): Int? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.let {
            runCatching { it.asInt }.getOrNull()
        }

    private fun JsonObject.doubleOr(name: String): Double? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.let {
            runCatching { it.asDouble }.getOrNull()
        }

    private fun JsonObject.longOr(name: String): Long? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.let {
            runCatching { it.asLong }.getOrNull()
        }

    private fun JsonObject.objOr(name: String): JsonObject? =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject
}
