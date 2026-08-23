package com.example.watchdog.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DeepSeekConsoleParserTest {

    // ===== users/get_user_summary =====

    @Test
    fun parseUserSummary_full() {
        val json = """
            {"code":0,"msg":"success","data":{"biz_data":{
              "current_token":12345,
              "total_usage":45.60,
              "monthly_usage":3.21,
              "total_available_token_estimation":999999,
              "normal_wallets":[
                {"balance":12.55,"currency":"CNY","token_estimation":100000},
                {"balance":5.00,"currency":"USD","token_estimation":20000}
              ],
              "bonus_wallets":[{"balance":0.10,"currency":"CNY","token_estimation":500}]
            }}}
        """.trimIndent()
        val s = DeepSeekConsoleParser.parseUserSummary(json)
        assertNotNull(s)
        assertEquals(2, s!!.wallets.size)
        // 首选 CNY 钱包
        assertEquals(12.55, s.primaryBalance!!.balance!!, 0.001)
        assertEquals("CNY", s.primaryBalance!!.currency)
        assertEquals(3.21, s.monthlyUsage!!, 0.001)
        assertEquals(45.60, s.totalUsage!!, 0.001)
        assertEquals(12345L, s.currentToken)
    }

    @Test
    fun parseUserSummary_fallbackWhenNoCny() {
        val json = """{"data":{"biz_data":{"normal_wallets":[{"balance":9.99,"currency":"USD"}]}}}"""
        val s = DeepSeekConsoleParser.parseUserSummary(json)
        assertNotNull(s)
        assertEquals(9.99, s!!.primaryBalance!!.balance, 0.001)
        assertEquals("USD", s.primaryBalance!!.currency)
    }

    @Test
    fun parseUserSummary_garbageReturnsNull() {
        assertNull(DeepSeekConsoleParser.parseUserSummary("""{"data":{"biz_data":{}}}"""))
        assertNull(DeepSeekConsoleParser.parseUserSummary("<html></html>"))
        assertNull(DeepSeekConsoleParser.parseUserSummary(null))
    }

    // ===== usage/by_api_key/cost =====

    @Test
    fun parseUsageCost_nestedStructure() {
        val json = """
            {"data":{"biz_data":{"start":1755014400,"end":1757600000,"bucket":"1d",
              "models":["deepseek-chat"],
              "data":[{"currency":"CNY","series":[
                {"api_key":"sk-abc","model":"deepseek-chat","buckets":[
                  {"time":1755014400,"cost":1.23},{"time":1755100800,"cost":2.00}]},
                {"api_key":"sk-def","model":"deepseek-chat","buckets":[
                  {"time":1755014400,"cost":0.50}]}
              ]}]}}}
        """.trimIndent()
        val list = DeepSeekConsoleParser.parseUsageCost(json)
        assertNotNull(list)
        assertEquals(1, list!!.size)
        val cs = list[0]
        assertEquals("CNY", cs.currency)
        assertEquals(2, cs.series.size)
        assertEquals("deepseek-chat", cs.series[0].model)
        assertEquals(2, cs.series[0].buckets.size)
        assertEquals(1755014400L, cs.series[0].buckets[0].timeSec)
        assertEquals(1.23, cs.series[0].buckets[0].cost, 0.001)
        assertEquals(0.50, cs.series[1].buckets[0].cost, 0.001)
    }

    @Test
    fun parseUsageCost_millisTimestampNormalized() {
        val json = """{"data":{"biz_data":{"data":[{"currency":"CNY","series":[
            {"api_key":"sk","model":"m","buckets":[{"time":1755014400000,"cost":1.0}]}]}]}}}"""
        val list = DeepSeekConsoleParser.parseUsageCost(json)
        assertEquals(1755014400L, list!![0].series[0].buckets[0].timeSec)
    }

    @Test
    fun parseUsageCost_garbageReturnsNull() {
        assertNull(DeepSeekConsoleParser.parseUsageCost("""{"data":{"biz_data":{}}}"""))
        assertNull(DeepSeekConsoleParser.parseUsageCost("<html></html>"))
    }

    // ===== usage/by_api_key/amount =====

    @Test
    fun parseUsageAmount_nestedUsageObject() {
        val json = """
            {"data":{"biz_data":{"series":[
              {"api_key":"sk-abc","model":"deepseek-chat","buckets":[
                {"time":1755014400,"usage":{"PROMPT_CACHE_HIT_TOKEN":10,"PROMPT_CACHE_MISS_TOKEN":100,"RESPONSE_TOKEN":50,"REQUEST":5}},
                {"time":1755100800,"usage":{"PROMPT_CACHE_HIT_TOKEN":0,"PROMPT_CACHE_MISS_TOKEN":200,"RESPONSE_TOKEN":80,"REQUEST":8}}
              ]}
            ]}}}
        """.trimIndent()
        val list = DeepSeekConsoleParser.parseUsageAmount(json)
        assertNotNull(list)
        assertEquals(1, list!!.size)
        val row = list[0]
        assertEquals("deepseek-chat", row.model)
        assertEquals(2, row.buckets.size)
        val b = row.buckets[0]
        assertEquals(10.0, b.promptCacheHit!!, 0.001)
        assertEquals(100.0, b.promptCacheMiss!!, 0.001)
        assertEquals(50.0, b.response!!, 0.001)
        assertEquals(5.0, b.request!!, 0.001)
    }

    @Test
    fun parseUsageAmount_garbageReturnsNull() {
        assertNull(DeepSeekConsoleParser.parseUsageAmount("<html></html>"))
        assertNull(DeepSeekConsoleParser.parseUsageAmount("""{"data":{}}"""))
    }

    // ===== 月度端点（usage/cost|amount?month=&year=）=====

    @Test
    fun parseMonthlyTotals_amountMode() {
        // 真机实测结构：biz_data 为数组（按账户/币种分组），每项含 total[]
        val json = """
            {"code":0,"data":{"biz_code":0,"biz_msg":"","biz_data":[{
              "total":[
                {"model":"deepseek-chat","usage":[
                  {"type":"REQUEST","amount":"128"},
                  {"type":"PROMPT_TOKEN","amount":"1000000"},
                  {"type":"PROMPT_CACHE_HIT_TOKEN","amount":"250000"},
                  {"type":"PROMPT_CACHE_MISS_TOKEN","amount":"750000"},
                  {"type":"RESPONSE_TOKEN","amount":"500000"}
                ]},
                {"model":"deepseek-reasoner","usage":[
                  {"type":"REQUEST","amount":"12"},
                  {"type":"RESPONSE_TOKEN","amount":"320000"}
                ]}
              ]
            }]}}
        """.trimIndent()
        val rows = DeepSeekConsoleParser.parseMonthlyTotals(json, costMode = false)
        assertNotNull(rows)
        assertEquals(2, rows!!.size)
        val chat = rows.first { it.model == "deepseek-chat" }
        assertEquals(128L, chat.requests)
        assertEquals(1_000_000L, chat.promptTokens)
        assertEquals(250_000L, chat.cacheHit)
        assertEquals(750_000L, chat.cacheMiss)
        assertEquals(500_000L, chat.outputTokens)
        assertEquals(0.0, chat.cost, 0.001)
        assertEquals(320_000L, rows.first { it.model == "deepseek-reasoner" }.outputTokens)
    }

    @Test
    fun parseMonthlyTotals_sameModelAcrossGroupsAggregated() {
        // 同名模型出现在多个分组时求和聚合
        val json = """
            {"data":{"biz_code":0,"biz_data":[
              {"total":[{"model":"m1","usage":[{"type":"REQUEST","amount":"10"}]}]},
              {"total":[{"model":"m1","usage":[{"type":"REQUEST","amount":"5"}]}]}
            ]}}
        """.trimIndent()
        val rows = DeepSeekConsoleParser.parseMonthlyTotals(json, costMode = false)
        assertNotNull(rows)
        assertEquals(1, rows!!.size)
        assertEquals(15L, rows[0].requests)
    }

    @Test
    fun parseMonthlyTotals_costModeAccumulatesMoney() {
        val json = """
            {"data":{"biz_code":0,"biz_data":[{"total":[
              {"model":"deepseek-chat","usage":[
                {"type":"PROMPT_TOKEN","amount":"12.34"},
                {"type":"RESPONSE_TOKEN","amount":"5.66"}
              ]}
            ]}]}}
        """.trimIndent()
        val rows = DeepSeekConsoleParser.parseMonthlyTotals(json, costMode = true)
        assertNotNull(rows)
        assertEquals(1, rows!!.size)
        assertEquals(18.0, rows[0].cost, 0.001)
        assertEquals(0L, rows[0].requests)
        assertEquals(0L, rows[0].promptTokens)
    }

    @Test
    fun parseMonthlyTotals_invalidParamReturnsNull() {
        // 真机实测：旧端点参数错误时返回 biz_code=1
        val json = """{"code":0,"data":{"biz_code":1,"biz_msg":"INVALID_PARAM","biz_data":null}}"""
        assertNull(DeepSeekConsoleParser.parseMonthlyTotals(json, costMode = true))
    }

    @Test
    fun parseMonthlyTotals_objectBizDataAlsoSupported() {
        // 防御：biz_data 为对象的旧形态
        val json = """{"data":{"biz_code":0,"biz_data":{"total":[{"model":"m","usage":[{"type":"REQUEST","amount":"3"}]}]}}}"""
        val rows = DeepSeekConsoleParser.parseMonthlyTotals(json, costMode = false)
        assertNotNull(rows)
        assertEquals(3L, rows!![0].requests)
    }

    @Test
    fun parseMonthlyTotals_garbageOrEmptyReturnsNull() {
        assertNull(DeepSeekConsoleParser.parseMonthlyTotals("not json", costMode = false))
        assertNull(DeepSeekConsoleParser.parseMonthlyTotals("""{"data":{"biz_data":[]}}""", costMode = false))
        assertNull(DeepSeekConsoleParser.parseMonthlyTotals("""{"data":{"biz_data":{"total":[]}}}""", costMode = false))
    }
}
