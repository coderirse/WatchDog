package io.github.coderirse.watchdog.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GlmCodingPlanParser 单元测试：智谱 Coding Plan 配额响应解析各分支。
 * 结构参考 cc-switch 实测（bigmodel.cn 与 z.ai 共用后端）。
 */
class GlmCodingPlanParserTest {

    @Test
    fun `成功解析套餐与双窗口`() {
        val json = """
            {"success":true,"data":{
              "level":"GLM Coding Lite",
              "limits":[
                {"type":"TOKENS_LIMIT","unit":3,"percentage":42.5,"nextResetTime":1700000000000},
                {"type":"TOKENS_LIMIT","unit":6,"percentage":63.0,"nextResetTime":1710000000000}
              ]
            }}
        """.trimIndent()
        val r = GlmCodingPlanParser.parse(json)
        assertTrue(r.success)
        assertEquals("GLM Coding Lite", r.planName)
        assertEquals(2, r.windows.size)
        assertEquals("5小时", r.windows[0].name)
        assertEquals(42.5, r.windows[0].usedPercent!!, 0.01)
        assertEquals(1700000000000L, r.windows[0].nextResetTime)
        assertEquals("周", r.windows[1].name)
    }

    @Test
    fun `success=false 返回业务错误`() {
        val json = """{"success":false,"msg":"invalid api key"}"""
        val r = GlmCodingPlanParser.parse(json)
        assertFalse(r.success)
        assertEquals("API 错误：invalid api key", r.errorMessage)
    }

    @Test
    fun `缺失 data 返回错误`() {
        val r = GlmCodingPlanParser.parse("""{"success":true}""")
        assertFalse(r.success)
        assertEquals("响应缺少 data 字段", r.errorMessage)
    }

    @Test
    fun `非 JSON 返回错误不抛异常`() {
        val r = GlmCodingPlanParser.parse("<html>waf</html>")
        assertFalse(r.success)
        assertEquals("响应不是有效 JSON", r.errorMessage)
    }

    @Test
    fun `limits 为空但 level 存在仍成功`() {
        val r = GlmCodingPlanParser.parse("""{"success":true,"data":{"level":"GLM Coding","limits":[]}}""")
        assertTrue(r.success)
        assertEquals("GLM Coding", r.planName)
        assertTrue(r.windows.isEmpty())
    }

    @Test
    fun `非 TOKENS_LIMIT 类型被忽略`() {
        val json = """
            {"success":true,"data":{"level":"GLM Coding","limits":[
              {"type":"OTHER","unit":3,"percentage":10,"nextResetTime":1},
              {"type":"TOKENS_LIMIT","unit":6,"percentage":80,"nextResetTime":2}
            ]}}
        """.trimIndent()
        val r = GlmCodingPlanParser.parse(json)
        assertTrue(r.success)
        assertEquals(1, r.windows.size)
        assertEquals("周", r.windows[0].name)
    }

    @Test
    fun `空字符串和空响应返回失败`() {
        assertFalse(GlmCodingPlanParser.parse("").success)
        assertFalse(GlmCodingPlanParser.parse(null).success)
        assertFalse(GlmCodingPlanParser.parse("   ").success)
    }
}
