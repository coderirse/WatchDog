package io.github.coderirse.watchdog.data.local

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.github.coderirse.watchdog.data.model.DailyModelUsage
import io.github.coderirse.watchdog.data.model.DailyUsage
import io.github.coderirse.watchdog.data.model.ModelUsage
import io.github.coderirse.watchdog.data.model.MonthlyUsageSource
import io.github.coderirse.watchdog.data.model.PlatformType
import io.github.coderirse.watchdog.data.model.QuotaInfo
import io.github.coderirse.watchdog.data.model.QuotaWindow
import java.lang.reflect.Type

/**
 * [QuotaInfo] 的 Gson 反序列化适配器。
 *
 * **为什么必须有这个类**：Gson 走反射直接写字段，完全绕过 Kotlin 构造函数与默认值。
 * 缓存 JSON 里缺失的任何字段都会被注入 `null` —— 哪怕它在 Kotlin 中声明为非空，
 * 之后任何读取（例如 `q.modelUsages.isEmpty()`）都会被编译器插入的 null 检查击穿成 NPE。
 * 已实测确认：不含 `modelUsages` 的 JSON 反序列化后该字段为 null
 * （见 `QuotaInfoDeserializationTest`）。
 *
 * 缓存又是"断网时唯一的兜底数据源"，一旦反序列化出脏数据，用户离线时会直接崩溃。
 * 因此这里做一次显式规范化：集合 → 空列表、字符串 → 安全默认值、枚举 → SERVER。
 *
 * 实现上刻意"手动逐字段读取 JSON"而不是 `parsed.copy(...)`：
 * 后者会因为 Kotlin 认为字段非空而让编译器把 `?:` 判为冗余（且无法防御运行时的 null）。
 * 手动读取后所有类型在编译期就是可空的，`?:` 是真实需要的。
 */
internal object QuotaInfoDeserializer : JsonDeserializer<QuotaInfo> {

    /** 缓存专用 Gson（写入仍用普通 Gson 输出，格式不变）。 */
    val cacheGson: Gson = GsonBuilder()
        .registerTypeAdapter(QuotaInfo::class.java, this)
        .create()

    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): QuotaInfo {
        val o = json.asJsonObject

        return QuotaInfo(
            platform = platformOf(o.stringOr("platform")),
            // 两个布尔字段缺失时视为"不可用/未配置"，比伪造可用更安全
            isAvailable = o.booleanOr("isAvailable") ?: false,
            isConfigured = o.booleanOr("isConfigured") ?: false,
            totalBalance = o.stringOr("totalBalance").orEmpty(),
            monthlyUsage = o.stringOr("monthlyUsage").orEmpty(),
            monthlyLimit = o.stringOr("monthlyLimit").orEmpty(),
            currency = o.stringOr("currency") ?: "CNY",
            availableBalance = o.stringOr("availableBalance"),
            errorMessage = o.stringOr("errorMessage"),
            modelUsages = o.arrayOrNull("modelUsages")
                ?.mapNotNull { item -> modelUsageOf(item.objectOrNull()) } ?: emptyList(),
            dailyUsage = o.arrayOrNull("dailyUsage")
                ?.mapNotNull { item -> dailyUsageOf(item.objectOrNull()) } ?: emptyList(),
            dailyModelUsage = o.arrayOrNull("dailyModelUsage")
                ?.mapNotNull { item -> dailyModelUsageOf(item.objectOrNull()) } ?: emptyList(),
            lastUpdated = o.longOr("lastUpdated") ?: System.currentTimeMillis(),
            isStale = o.booleanOr("isStale") ?: false,
            isEstimate = o.booleanOr("isEstimate") ?: false,
            planName = o.stringOr("planName"),
            quotaWindows = o.arrayOrNull("quotaWindows")
                ?.mapNotNull { item -> quotaWindowOf(item.objectOrNull()) } ?: emptyList(),
            boosterInfo = o.stringOr("boosterInfo"),
            dataSourceLabel = o.stringOr("dataSourceLabel"),
            consoleDiag = o.stringOr("consoleDiag"),
            needsRelogin = o.booleanOr("needsRelogin") ?: false,
            monthlyUsageSource = monthlyUsageSourceOf(o.stringOr("monthlyUsageSource"))
        )
    }

    // ===== 嵌套元素的手动规范化 =====
    //
    // 旧实现把嵌套列表元素交给 plainGson 反射反序列化，防线只覆盖了 QuotaInfo 顶层。
    // 反射同样绕过 Kotlin 默认值：缺字段 → 非空字段被注入 null（如 ModelUsage.modelName、
    // DailyUsage.platform/date）；未知枚举名 → Gson 默认适配器返回 null 而不抛异常，
    // runCatching 拦不住。UI 一旦读取即 NPE——恰是本类宣称要防的"离线兜底脏数据"。
    // 故嵌套类型也逐字段手动读取：非空字段缺失/枚举未知 → 整个元素视为脏数据丢弃。

    private fun JsonElement.objectOrNull(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject

    private fun modelUsageOf(o: JsonObject?): ModelUsage? {
        o ?: return null
        return ModelUsage(
            modelName = o.stringOr("modelName") ?: return null,
            requestCount = o.longOr("requestCount") ?: 0,
            totalTokens = o.longOr("totalTokens") ?: 0,
            inputTokens = o.longOr("inputTokens") ?: 0,
            outputTokens = o.longOr("outputTokens") ?: 0,
            cost = o.stringOrNumber("cost") ?: "0.00"
        )
    }

    private fun dailyUsageOf(o: JsonObject?): DailyUsage? {
        o ?: return null
        return DailyUsage(
            date = o.stringOr("date") ?: return null,
            platform = platformOrNull(o.stringOr("platform")) ?: return null,
            totalTokens = o.longOr("totalTokens") ?: 0,
            inputTokens = o.longOr("inputTokens") ?: 0,
            outputTokens = o.longOr("outputTokens") ?: 0,
            requests = o.longOr("requests") ?: 0,
            cost = o.doubleOr("cost")
        )
    }

    private fun dailyModelUsageOf(o: JsonObject?): DailyModelUsage? {
        o ?: return null
        return DailyModelUsage(
            date = o.stringOr("date") ?: return null,
            platform = platformOrNull(o.stringOr("platform")) ?: return null,
            model = o.stringOr("model") ?: return null,
            totalTokens = o.longOr("totalTokens") ?: 0,
            inputTokens = o.longOr("inputTokens") ?: 0,
            outputTokens = o.longOr("outputTokens") ?: 0,
            requests = o.longOr("requests") ?: 0,
            cost = o.doubleOr("cost")
        )
    }

    private fun quotaWindowOf(o: JsonObject?): QuotaWindow? {
        o ?: return null
        // QuotaWindow 全字段可空：任何字段缺失都不影响其余字段展示
        return QuotaWindow(
            name = o.stringOr("name"),
            used = o.doubleOr("used"),
            remaining = o.doubleOr("remaining"),
            limit = o.doubleOr("limit"),
            resetTime = o.longOr("resetTime"),
            expiresAt = o.longOr("expiresAt")
        )
    }

    private fun platformOrNull(name: String?): PlatformType? =
        name?.let { runCatching { PlatformType.valueOf(it) }.getOrNull() }

    private fun JsonObject.arrayOrNull(name: String): List<JsonElement>? =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.toList()

    private fun JsonObject.stringOr(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.booleanOr(name: String): Boolean? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    private fun JsonObject.longOr(name: String): Long? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong

    private fun JsonObject.doubleOr(name: String): Double? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble

    /** 字符串或数字原语统一取文本（防御旧缓存把 String 字段写成数字）。 */
    private fun JsonObject.stringOrNumber(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun platformOf(name: String?): PlatformType =
        name?.let { runCatching { PlatformType.valueOf(it) }.getOrNull() } ?: PlatformType.DEEPSEEK

    private fun monthlyUsageSourceOf(name: String?): MonthlyUsageSource =
        name?.let { runCatching { MonthlyUsageSource.valueOf(it) }.getOrNull() }
            ?: MonthlyUsageSource.SERVER
}
