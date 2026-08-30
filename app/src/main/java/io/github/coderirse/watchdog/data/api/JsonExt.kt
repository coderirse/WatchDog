package io.github.coderirse.watchdog.data.api

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 控制台/非官方接口响应的共享防御性 JSON 工具（data/api 各解析器共用）。
 *
 * 约定与所有 Parser 一致：字段缺失/类型不符一律返回 null，绝不抛异常到调用方；
 * 全部纯函数，便于单元测试。
 */
internal object JsonExt {

    /** 宽松解析 JSON 文本；空串/非法 JSON/JSON null 返回 null。 */
    fun parseJson(text: String?): JsonElement? {
        if (text.isNullOrBlank()) return null
        return runCatching { JsonParser.parseString(text) }
            .getOrNull()?.takeUnless { it.isJsonNull }
    }

    /** 解一层包裹：{data: {...}} / {result: {...}} / {resData: {...}} 取其内部对象；否则返回根对象。 */
    fun unwrapObject(root: JsonElement): JsonObject? {
        if (!root.isJsonObject) return null
        val o = root.asJsonObject
        val inner = o.get("data") ?: o.get("result") ?: o.get("resData") ?: o.get("res_data")
        return if (inner != null && inner.isJsonObject) inner.asJsonObject else o
    }

    /** 候选键列表取第一个可解析的非空字符串。 */
    fun findString(o: JsonObject, keys: List<String>): String? {
        for (key in keys) {
            val el = o.get(key) ?: continue
            if (el.isJsonPrimitive && el.asJsonPrimitive.isString) {
                val s = el.asString.trim()
                if (s.isNotEmpty()) return s
            }
        }
        return null
    }

    /** 候选键列表取第一个可解析的数字（数字或数字字符串）。 */
    fun findNumber(o: JsonObject, keys: List<String>): Double? {
        for (key in keys) {
            val el = o.get(key) ?: continue
            val v = toDoubleOrNull(el)
            if (v != null) return v
        }
        return null
    }

    /** 候选键列表取第一个可解析的时间（epoch 秒/毫秒或字符串），统一返回 epoch millis。 */
    fun findTime(o: JsonObject, keys: List<String>): Long? {
        for (key in keys) {
            val el = o.get(key) ?: continue
            val v = toEpochMillisOrNull(el)
            if (v != null) return v
        }
        return null
    }

    /** 数值兜底解析：数字或数字字符串。 */
    fun toDoubleOrNull(el: JsonElement?): Double? {
        if (el == null || el.isJsonNull || !el.isJsonPrimitive) return null
        return runCatching {
            val p = el.asJsonPrimitive
            if (p.isNumber) p.asDouble else p.asString.toDoubleOrNull()
        }.getOrNull()
    }

    /** 时间兜底解析：epoch 秒/毫秒数字或 ISO-8601 / "yyyy-MM-dd HH:mm:ss" 字符串，统一返回 epoch millis。 */
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

    /** JsonObject 安全取字符串（非字符串/缺失返回 null）。 */
    fun JsonObject.strOrNull(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    /** JsonObject 安全取对象数组（缺失/非数组返回 null）。 */
    fun JsonObject.arrayOrNull(name: String): List<JsonElement>? {
        val el = get(name) ?: return null
        return if (el.isJsonArray) el.asJsonArray.toList() else null
    }
}
