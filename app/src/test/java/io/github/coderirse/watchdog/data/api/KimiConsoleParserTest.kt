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

    @Test
    fun `千分位金额正确提取`() {
        val html = "<span>账户余额</span><b>¥1,234.56</b>"
        val r = KimiConsoleParser.parse(html)
        assertNotNull(r)
        assertEquals("1,234.56", r!!.balance)
    }

    @Test
    fun `整数金额经货币符号锚定提取`() {
        // 旧正则强制要求小数点，¥125 会被漏采成假 0
        val html = "<span>账户余额</span><b>¥125</b>"
        val r = KimiConsoleParser.parse(html)
        assertNotNull(r)
        assertEquals("125", r!!.balance)
    }

    @Test
    fun `日期与版本号不再被误当金额`() {
        // 旧盲窗匹配把 2026.10.02 匹配成 026.10、kimi-k2.5-turbo 匹配成 2.5
        val html = "<span>账户余额</span><em>更新于 2026.10.02</em><em>kimi-k2.5-turbo</em>"
        assertNull(KimiConsoleParser.parse(html))
    }

    @Test
    fun `转义负载下全部中文关键词可用`() {
        // 旧转义表只覆盖 9 个字，"消耗/可提现"等备选关键词在 \uXXXX 转义下永远失配
        val html = "\\u53ef\\u63d0\\u73b0\\u4f59\\u989d 8.88 \\u672c\\u6708\\u6d88\\u8017 3.14"
        val r = KimiConsoleParser.parse(html)
        assertNotNull(r)
        assertEquals("8.88", r!!.balance)
        assertEquals("3.14", r.monthCost)
    }
}
