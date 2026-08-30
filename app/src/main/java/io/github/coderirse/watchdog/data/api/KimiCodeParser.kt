package io.github.coderirse.watchdog.data.api

import io.github.coderirse.watchdog.data.model.QuotaWindow
import com.google.gson.JsonElement
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Kimi Code GET v1/usages 响应的防御性解析。
 *
 * 该接口响应结构【未完整实测】（2026-08 验证仅得 401），字段名与类型均为合理猜测。
 * 因此所有解析均做防御性兜底：任何字段缺失或类型不符都返回 null，而非抛异常。
 * 全部为纯函数，独立成对象以便单元测试。
 */
object KimiCodeParser {

    /** plan 字段可能是字符串（"Pro"）或对象（{"name": ...} / {"planName": ...}） */
    fun parsePlanName(plan: JsonElement?): String? {
        if (plan == null || plan.isJsonNull) return null
        return runCatching {
            when {
                plan.isJsonPrimitive -> plan.asString.takeIf { it.isNotBlank() }
                plan.isJsonObject -> {
                    val obj = plan.asJsonObject
                    listOf("name", "planName", "plan_name", "title", "tier")
                        .firstNotNullOfOrNull { key ->
                            obj.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
                        }
                }
                else -> null
            }
        }.getOrNull()
    }

    /** 解析单个配额窗口，剩余额度缺失但给了 used + limit 时兜底推算。 */
    fun parseWindow(w: KimiCodeQuotaWindow): QuotaWindow {
        val used = toDoubleOrNull(w.used)
        val limit = toDoubleOrNull(w.limit)
        val remaining = toDoubleOrNull(w.remaining)
            ?: if (used != null && limit != null) (limit - used).coerceAtLeast(0.0) else null
        return QuotaWindow(
            name = normalizeWindowName(extractWindowName(w.name)),
            used = used,
            remaining = remaining,
            limit = limit,
            resetTime = toEpochMillisOrNull(w.resetTime),
            expiresAt = toEpochMillisOrNull(w.expiresAt)
        )
    }

    /**
     * 窗口名字段可能是字符串或对象（2026-08 真机实测 window 为对象）。
     * 对象时优先取常见名称键；其次尝试时长字段（seconds 等）换算为 "N小时"。
     */
    fun extractWindowName(el: JsonElement?): String? {
        if (el == null || el.isJsonNull) return null
        return runCatching {
            when {
                el.isJsonPrimitive -> el.asString.takeIf { it.isNotBlank() }
                el.isJsonObject -> {
                    val obj = el.asJsonObject
                    listOf("name", "type", "label", "period", "title", "window")
                        .firstNotNullOfOrNull { key ->
                            obj.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
                        }
                        ?: listOf("seconds", "durationSeconds", "duration_seconds")
                            .firstNotNullOfOrNull { key -> toDoubleOrNull(obj.get(key)) }
                            ?.let { seconds -> "${(seconds / 3600).toInt()}小时" }
                }
                else -> null
            }
        }.getOrNull()
    }

    /** 窗口名称归一化为 "5小时" / "周" / "月"，无法识别时保留原文。 */
    fun normalizeWindowName(raw: String?): String? {
        val s = raw?.trim()?.lowercase(Locale.US) ?: return null
        return when {
            s.contains("5") && (s.contains("hour") || s.contains("小时") || s == "5h") -> "5小时"
            s.contains("week") || s.contains("周") || s.contains("weekly") -> "周"
            s.contains("month") || s.contains("月") || s.contains("monthly") -> "月"
            else -> raw.trim().ifBlank { null }
        }
    }

    /** booster 类型未知：字符串直接用，对象取常见描述字段，数组取首项。 */
    fun parseBooster(booster: JsonElement?): String? {
        if (booster == null || booster.isJsonNull) return null
        return runCatching {
            when {
                booster.isJsonPrimitive -> booster.asString.takeIf { it.isNotBlank() }
                booster.isJsonObject -> {
                    val obj = booster.asJsonObject
                    listOf("description", "name", "title", "info", "status")
                        .firstNotNullOfOrNull { key ->
                            obj.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
                        } ?: obj.toString()
                }
                booster.isJsonArray -> booster.asJsonArray.firstOrNull()?.let { parseBooster(it) }
                else -> null
            }
        }.getOrNull()
    }

    /** 数值兜底解析：数字或数字字符串。 */
    fun toDoubleOrNull(el: JsonElement?): Double? {
        if (el == null || el.isJsonNull || !el.isJsonPrimitive) return null
        return runCatching {
            val p = el.asJsonPrimitive
            if (p.isNumber) p.asDouble else p.asString.toDoubleOrNull()
        }.getOrNull()
    }

    /** 时间兜底解析：epoch 秒/毫秒数字或 ISO-8601 字符串，统一返回 epoch millis。 */
    fun toEpochMillisOrNull(el: JsonElement?): Long? {
        if (el == null || el.isJsonNull || !el.isJsonPrimitive) return null
        return runCatching {
            val p = el.asJsonPrimitive
            if (p.isNumber) {
                val v = p.asLong
                // 小于 1e12 视为秒级时间戳
                if (v < 1_000_000_000_000L) v * 1000 else v
            } else {
                val s = p.asString
                s.toLongOrNull()?.let { v -> return@runCatching if (v < 1_000_000_000_000L) v * 1000 else v }
                parseIso8601(s)
                    ?: runCatching {
                        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(s)?.time
                    }.getOrNull()
            }
        }.getOrNull()
    }

    /** ISO-8601 解析（如 2024-01-01T00:00:00Z / 带毫秒 / 带时区偏移），minSdk 24 兼容，不用 java.time。 */
    fun parseIso8601(s: String): Long? {
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX"
        )
        for (pattern in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                }.parse(s)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }
}
