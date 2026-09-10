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
                ?.mapNotNull { item -> gsonOrNull<ModelUsage>(item) } ?: emptyList(),
            dailyUsage = o.arrayOrNull("dailyUsage")
                ?.mapNotNull { item -> gsonOrNull<DailyUsage>(item) } ?: emptyList(),
            dailyModelUsage = o.arrayOrNull("dailyModelUsage")
                ?.mapNotNull { item -> gsonOrNull<DailyModelUsage>(item) } ?: emptyList(),
            lastUpdated = o.longOr("lastUpdated") ?: System.currentTimeMillis(),
            isStale = o.booleanOr("isStale") ?: false,
            isEstimate = o.booleanOr("isEstimate") ?: false,
            planName = o.stringOr("planName"),
            quotaWindows = o.arrayOrNull("quotaWindows")
                ?.mapNotNull { item -> gsonOrNull<QuotaWindow>(item) } ?: emptyList(),
            boosterInfo = o.stringOr("boosterInfo"),
            dataSourceLabel = o.stringOr("dataSourceLabel"),
            consoleDiag = o.stringOr("consoleDiag"),
            needsRelogin = o.booleanOr("needsRelogin") ?: false,
            monthlyUsageSource = monthlyUsageSourceOf(o.stringOr("monthlyUsageSource"))
        )
    }

    /** 用普通 Gson 解析嵌套对象（不经过本适配器，故不会递归到 QuotaInfo 本身）。 */
    private inline fun <reified T> gsonOrNull(el: JsonElement): T? =
        runCatching { plainGson.fromJson(el, T::class.java) }.getOrNull()

    private fun JsonObject.arrayOrNull(name: String): List<JsonElement>? =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.toList()

    private fun JsonObject.stringOr(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.booleanOr(name: String): Boolean? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    private fun JsonObject.longOr(name: String): Long? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong

    private fun platformOf(name: String?): PlatformType =
        name?.let { runCatching { PlatformType.valueOf(it) }.getOrNull() } ?: PlatformType.DEEPSEEK

    private fun monthlyUsageSourceOf(name: String?): MonthlyUsageSource =
        name?.let { runCatching { MonthlyUsageSource.valueOf(it) }.getOrNull() }
            ?: MonthlyUsageSource.SERVER

    private val plainGson: Gson = Gson()
}
