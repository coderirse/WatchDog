package io.github.coderirse.watchdog.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiMoConsoleParserTest {

    // ===== tokenPlan/detail =====

    @Test
    fun parseDetail_flatObject() {
        val json = """
            {"data":{"planName":"Pro 套餐","totalCredits":10000,"usedCredits":6200,
            "expireTime":1767225600000,"cycle":"月度续订"}}
        """.trimIndent()
        val detail = MiMoConsoleParser.parseTokenPlanDetail(json)
        assertNotNull(detail)
        assertEquals("Pro 套餐", detail!!.planName)
        assertEquals(10000.0, detail.totalCredits!!, 0.001)
        assertEquals(6200.0, detail.usedCredits!!, 0.001)
        assertEquals(1767225600000L, detail.expiresAt!!)
        assertEquals("月度续订", detail.cycleLabel)
    }

    @Test
    fun parseDetail_nestedSubscription() {
        val json = """
            {"result":{"subscription":{"package_name":"Standard","credits":5000,
            "valid_until":"2026-03-01T00:00:00Z"}}}
        """.trimIndent()
        val detail = MiMoConsoleParser.parseTokenPlanDetail(json)
        assertNotNull(detail)
        assertEquals("Standard", detail!!.planName)
        assertEquals(5000.0, detail.totalCredits!!, 0.001)
        assertNotNull(detail.expiresAt)
    }

    @Test
    fun parseDetail_unrecognizableReturnsNull() {
        assertNull(MiMoConsoleParser.parseTokenPlanDetail("""{"code":401}"""))
        assertNull(MiMoConsoleParser.parseTokenPlanDetail("<html>redirect to login</html>"))
        assertNull(MiMoConsoleParser.parseTokenPlanDetail(null))
    }

    // ===== tokenPlan/usage =====

    @Test
    fun parseUsage_arrayOfWindows() {
        val json = """
            {"data":{"usage":[{"name":"月度","used":6200,"limit":10000,
            "reset_time":1767225600,"expires_at":"2026-03-01T00:00:00Z"}]}}
        """.trimIndent()
        val windows = MiMoConsoleParser.parseTokenPlanUsage(json)
        assertEquals(1, windows.size)
        val w = windows[0]
        assertEquals("月度", w.name)
        assertEquals(6200.0, w.used!!, 0.001)
        assertEquals(10000.0, w.limit!!, 0.001)
        // 未给 remaining 时由 used/limit 推算
        assertEquals(3800.0, w.remaining!!, 0.001)
        assertNotNull(w.resetTime)
        assertNotNull(w.expiresAt)
    }

    @Test
    fun parseUsage_singleObjectAsWindow() {
        val json = """{"data":{"quota":8000,"consumedCredits":2000}}"""
        val windows = MiMoConsoleParser.parseTokenPlanUsage(json)
        assertTrue(windows.isNotEmpty())
        val w = windows[0]
        assertEquals("套餐", w.name)
        assertEquals(8000.0, w.limit!!, 0.001)
        assertEquals(2000.0, w.used!!, 0.001)
        assertEquals(6000.0, w.remaining!!, 0.001)
    }

    @Test
    fun parseUsage_garbageReturnsEmpty() {
        assertTrue(MiMoConsoleParser.parseTokenPlanUsage("""{"code":401}""").isEmpty())
        assertTrue(MiMoConsoleParser.parseTokenPlanUsage("<html></html>").isEmpty())
    }

    // ===== 余额探测 =====

    @Test
    fun parseBalance_commonFields() {
        val json = """{"data":{"balance":"88.50","currency":"CNY","frozenBalance":0}}"""
        val b = MiMoConsoleParser.parseBalanceInfo(json)
        assertNotNull(b)
        assertEquals(88.5, b!!.balance, 0.001)
        assertEquals("CNY", b.currency)
        assertEquals(0.0, b.frozenBalance!!, 0.001)
    }

    @Test
    fun parseBalance_alternateFields() {
        val json = """{"cash_balance":"12.34","gift_balance":5.66,"unit":"CNY"}"""
        val b = MiMoConsoleParser.parseBalanceInfo(json)
        assertNotNull(b)
        assertEquals(12.34, b!!.balance, 0.001)
        assertEquals(5.66, b.giftBalance!!, 0.001)
        assertEquals("CNY", b.currency)
    }

    @Test
    fun parseBalance_numericBalanceField() {
        val json = """{"balance": 99.99}"""
        val b = MiMoConsoleParser.parseBalanceInfo(json)
        assertEquals(99.99, b!!.balance, 0.001)
    }

    @Test
    fun parseBalance_htmlOrEmptyReturnsNull() {
        assertNull(MiMoConsoleParser.parseBalanceInfo("<html><body>login</body></html>"))
        assertNull(MiMoConsoleParser.parseBalanceInfo("""{"message":"not found"}"""))
        assertNull(MiMoConsoleParser.parseBalanceInfo(null))
    }

    // ===== 工具函数 =====

    @Test
    fun findNumber_stringOrNumeric() {
        @Suppress("DEPRECATION")
        val o = com.google.gson.JsonParser().parse("""{"a":"3.5","b":4,"c":null}""")
            .asJsonObject
        assertEquals(3.5, MiMoConsoleParser.findNumber(o, listOf("a"))!!, 0.001)
        assertEquals(4.0, MiMoConsoleParser.findNumber(o, listOf("b"))!!, 0.001)
        assertNull(MiMoConsoleParser.findNumber(o, listOf("c")))
        assertNull(MiMoConsoleParser.findNumber(o, listOf("missing")))
    }
}
