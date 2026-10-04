package io.github.coderirse.watchdog.data.model

import com.google.gson.Gson
import io.github.coderirse.watchdog.data.local.QuotaInfoDeserializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 额度缓存的反序列化兼容性测试。
 *
 * QuotaCacheStore 用 Gson 直接反序列化 QuotaInfo 并作为断网回退数据源，
 * 而 Gson 走反射绕过 Kotlin 构造函数的默认值：JSON 里缺失的字段会被注入 null，
 * 即使该属性在 Kotlin 中声明为非空且有默认值 —— 之后 UI 一读就 NPE。
 *
 * 本测试验证 [QuotaInfoDeserializer] 这道防线的两个职责：
 * 1. 旧 JSON（缺字段）能被规范化为可安全使用的对象（集合不为 null、字符串有默认值）；
 * 2. 新增的枚举字段缺失时降级为 SERVER，不抛异常。
 */
class QuotaInfoDeserializationTest {

    /** 与生产读取路径完全一致的 Gson 实例（带规范化适配器）。 */
    private val gson = QuotaInfoDeserializer.cacheGson

    /** v1.8.0 及更早版本写入的缓存 JSON（缺少多个后来新增的字段）。 */
    private val legacyJson = """
        {
          "platform": "DEEPSEEK",
          "isAvailable": true,
          "isConfigured": true,
          "totalBalance": "36.50",
          "monthlyUsage": "12.34",
          "monthlyLimit": "0",
          "currency": "CNY",
          "lastUpdated": 1757000000000,
          "isStale": false,
          "isEstimate": false,
          "needsRelogin": false
        }
    """.trimIndent()

    @Test
    fun `旧版本缓存缺少集合字段时被规范化为空列表`() {
        val quota = gson.fromJson(legacyJson, QuotaInfo::class.java)

        assertEquals(PlatformType.DEEPSEEK, quota.platform)
        assertEquals("36.50", quota.totalBalance)
        assertEquals("12.34", quota.monthlyUsage)
        // 关键断言：这三个集合字段必须非 null，否则 UI 读取即 NPE
        assertTrue(quota.modelUsages.isEmpty())
        assertTrue(quota.dailyUsage.isEmpty())
        assertTrue(quota.dailyModelUsage.isEmpty())
        assertTrue(quota.quotaWindows.isEmpty())
    }

    @Test
    fun `缺失的枚举字段降级为 SERVER`() {
        val quota = gson.fromJson(legacyJson, QuotaInfo::class.java)

        assertEquals(MonthlyUsageSource.SERVER, quota.monthlyUsageSource)
        assertFalse(quota.isMonthlyUsageEstimated)
    }

    @Test
    fun `枚举字段存在时正常保留`() {
        val json = legacyJson.replace(
            "\"isStale\": false,",
            "\"isStale\": false, \"monthlyUsageSource\": \"LOCAL_ESTIMATE\","
        )
        val quota = gson.fromJson(json, QuotaInfo::class.java)

        assertEquals(MonthlyUsageSource.LOCAL_ESTIMATE, quota.monthlyUsageSource)
        assertTrue(quota.isMonthlyUsageEstimated)
    }

    @Test
    fun `非法枚举值不抛异常且降级为默认值`() {
        val json = legacyJson.replace(
            "\"isStale\": false,",
            "\"isStale\": false, \"monthlyUsageSource\": \"SOMETHING_NEW\","
        )

        val quota = gson.fromJson(json, QuotaInfo::class.java)

        assertEquals(MonthlyUsageSource.SERVER, quota.monthlyUsageSource)
    }

    @Test
    fun `非字符串类型的余额字段被规范化为空串而非 null`() {
        val json = legacyJson.replace("\"totalBalance\": \"36.50\",", "\"totalBalance\": 36.5,")

        val quota = gson.fromJson(json, QuotaInfo::class.java)

        // 数字形态的余额无法直接映射到 String 字段，规范化为空串（UI 显示占位符）
        assertEquals("", quota.totalBalance)
    }

    @Test
    fun `嵌套集合元素正常解析`() {
        val original = QuotaInfo(
            platform = PlatformType.DEEPSEEK,
            isAvailable = true,
            isConfigured = true,
            totalBalance = "36.50",
            modelUsages = listOf(
                ModelUsage("deepseek-chat", requestCount = 12, totalTokens = 1_000),
                ModelUsage("deepseek-reasoner", requestCount = 3, totalTokens = 500)
            ),
            dailyModelUsage = listOf(
                DailyModelUsage(
                    date = "2026-09-10",
                    platform = PlatformType.DEEPSEEK,
                    model = "deepseek-chat",
                    totalTokens = 1_000,
                    requests = 12,
                    cost = 0.42
                )
            ),
            quotaWindows = listOf(QuotaWindow(name = "周", used = 8.0, remaining = 2.0, limit = 10.0)),
            monthlyUsageSource = MonthlyUsageSource.LOCAL_ESTIMATE
        )

        val restored = gson.fromJson(Gson().toJson(original), QuotaInfo::class.java)

        assertEquals(2, restored.modelUsages.size)
        assertEquals("deepseek-chat", restored.modelUsages.first().modelName)
        assertEquals(1_000L, restored.modelUsages.first().totalTokens)
        assertEquals(1, restored.dailyModelUsage.size)
        assertEquals(0.42, restored.dailyModelUsage.first().cost!!, 0.0001)
        assertEquals(1, restored.quotaWindows.size)
        assertEquals("周", restored.quotaWindows.first().name)
        assertEquals(MonthlyUsageSource.LOCAL_ESTIMATE, restored.monthlyUsageSource)
    }

    @Test
    fun `写入侧不输出 null 字段`() {
        val json = Gson().toJson(QuotaInfo.notConfigured(PlatformType.GLM))

        // 断言写入格式稳定（null 字段被省略），避免缓存体积膨胀与解析歧义
        assertFalse(json.contains(":null"))
    }

    @Test
    fun `嵌套列表脏元素被丢弃而非注入 null`() {
        // 嵌套元素此前走 plainGson 反射：缺 modelName 的 ModelUsage 会被注入 null，
        // 未知枚举名的 platform 也会被注入 null，UI 读取即 NPE。
        // 防线应把脏元素整体丢弃、合法元素保留。
        val json = """
            {
              "platform": "DEEPSEEK",
              "isConfigured": true,
              "isAvailable": true,
              "modelUsages": [
                {"totalTokens": 5},
                {"modelName": "deepseek-chat", "totalTokens": 100}
              ],
              "dailyModelUsage": [
                {"date": "2026-10-01", "model": "m1"},
                {"date": "2026-10-01", "platform": "DEEPSEEK", "model": "m2", "cost": 1.25}
              ],
              "dailyUsage": [
                {"platform": "DEEPSEEK", "date": "2026-10-01"}
              ]
            }
        """.trimIndent()
        val q = gson.fromJson(json, QuotaInfo::class.java)
        assertEquals(1, q.modelUsages.size)
        assertEquals("deepseek-chat", q.modelUsages.first().modelName)
        assertEquals(1, q.dailyModelUsage.size)
        assertEquals("m2", q.dailyModelUsage.first().model)
        assertEquals(1.25, q.dailyModelUsage.first().cost!!, 0.0001)
        assertEquals(1, q.dailyUsage.size)
        assertEquals("DEEPSEEK", q.dailyUsage.first().platform.name)
    }
}

