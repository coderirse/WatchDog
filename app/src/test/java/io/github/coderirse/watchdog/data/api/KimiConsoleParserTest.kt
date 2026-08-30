package io.github.coderirse.watchdog.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * KimiConsoleParser 单元测试：从 SSR HTML 提取余额/消费金额。
 */
class KimiConsoleParserTest {

    @Test
    fun `普通 HTML 提取四个金额`() {
        val html = """
            <div>账户余额</div><span>7.56019</span>
            <div>今日消费</div><span>0.00000</span>
            <div>本月消费</div><span>1.73004</span>
            <div>总消费</div><span>125.70595</span>
        """.trimIndent()
        val r = KimiConsoleParser.parse(html)
        assertNotNull(r)
        assertEquals("7.56019", r!!.balance)
        assertEquals("0.00000", r.todayCost)
        assertEquals("1.73004", r.monthCost)
        assertEquals("125.70595", r.totalCost)
    }

    @Test
    fun `标签与金额被 HTML 标签分隔仍能提取`() {
        val html = "<span>余额</span><b>¥</b><i>7.56019</i>"
        val r = KimiConsoleParser.parse(html)
        assertNotNull(r)
        assertEquals("7.56019", r!!.balance)
    }

    @Test
    fun `JSON 转义中文仍能提取`() {
        val html = "\\u4f59\\u989d 7.56 \\u4eca\\u65e5\\u6d88\\u8d39 0.00 \\u672c\\u6708\\u6d88\\u8d39 1.73"
        val r = KimiConsoleParser.parse(html)
        assertNotNull(r)
        assertEquals("7.56", r!!.balance)
        assertEquals("0.00", r.todayCost)
        assertEquals("1.73", r.monthCost)
    }

    @Test
    fun `无金额返回 null`() {
        assertNull(KimiConsoleParser.parse("<html>登录</html>"))
        assertNull(KimiConsoleParser.parse(null))
        assertNull(KimiConsoleParser.parse(""))
    }
}
