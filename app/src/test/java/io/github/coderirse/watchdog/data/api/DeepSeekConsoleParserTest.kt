package io.github.coderirse.watchdog.data.api

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

    // ===== parseMonthlyDays（按天）=====

    @Test
    fun parseMonthlyDays_amountMode() {
        // 真机结构：biz_data 为数组，days[] 每项 {date, data:[{usage:[{type,amount}]}]}
        val json = """
            {"code":0,"data":{"biz_code":0,"biz_data":[{
              "days":[
                {"date":"2026-08-22","data":[{"usage":[
                  {"type":"REQUEST","amount":"5"},
                  {"type":"PROMPT_TOKEN","amount":"1000"},
                  {"type":"PROMPT_CACHE_HIT_TOKEN","amount":"200"},
                  {"type":"PROMPT_CACHE_MISS_TOKEN","amount":"800"},
                  {"type":"RESPONSE_TOKEN","amount":"400"}
                ]}]},
                {"date":"2026-08-23","data":[{"usage":[
                  {"type":"REQUEST","amount":"3"},
                  {"type":"RESPONSE_TOKEN","amount":"250"}
                ]}]}
              ]
            }]}}
        """.trimIndent()
        val rows = DeepSeekConsoleParser.parseMonthlyDays(json, costMode = false)
        assertNotNull(rows)
        assertEquals(2, rows!!.size)
        val d22 = rows.first { it.date == "2026-08-22" }
        assertEquals(5L, d22.requests)
        // 输入 = prompt(1000) + hit(200) + miss(800) = 2000（真机实测 PROMPT_TOKEN 恒为 0，真实输入在缓存项里）
        assertEquals(2000L, d22.inputTokens)
        assertEquals(200L, d22.cacheHit)
        assertEquals(800L, d22.cacheMiss)
        assertEquals(400L, d22.outputTokens)
        assertEquals(2400L, d22.totalTokens)
        assertEquals(0.0, d22.cost, 0.001)
    }

    @Test
    fun parseMonthlyDays_costModeAccumulatesMoney() {
        val json = """
            {"data":{"biz_code":0,"biz_data":[{"days":[
              {"date":"2026-08-23","data":[{"usage":[
                {"type":"PROMPT_TOKEN","amount":"12.34"},
                {"type":"RESPONSE_TOKEN","amount":"5.66"}
              ]}]}
            ]}]}}
        """.trimIndent()
        val rows = DeepSeekConsoleParser.parseMonthlyDays(json, costMode = true)
        assertNotNull(rows)
        assertEquals(1, rows!!.size)
        assertEquals(18.0, rows[0].cost, 0.001)
        assertEquals(0L, rows[0].requests)
    }

    @Test
    fun parseMonthlyDays_objectBizDataAlsoSupported() {
        val json = """{"data":{"biz_code":0,"biz_data":{"days":[{"date":"2026-08-23","data":[{"usage":[{"type":"REQUEST","amount":"7"}]}]}]}}}"""
        val rows = DeepSeekConsoleParser.parseMonthlyDays(json, costMode = false)
        assertNotNull(rows)
        assertEquals(7L, rows!![0].requests)
    }

    @Test
    fun parseMonthlyDays_emptyOrGarbageReturnsNull() {
        assertNull(DeepSeekConsoleParser.parseMonthlyDays("not json", costMode = false))
        assertNull(DeepSeekConsoleParser.parseMonthlyDays("""{"data":{"biz_data":[{"days":[]}]}}""", costMode = false))
        assertNull(DeepSeekConsoleParser.parseMonthlyDays("""{"data":{"biz_data":[]}}""", costMode = false))
    }

    // ===== parseMonthlyDaysByModel（按模型 × 按天）=====

    @Test
    fun parseMonthlyDaysByModel_amountMode_objectBizData() {
        // 真机实测结构：amount 端点 biz_data 为对象 {total, days}，days[].data[] 每项 {model, usage[]}
        val json = """
            {"code":0,"data":{"biz_code":0,"biz_msg":"","biz_data":{
              "total":[],
              "days":[
                {"date":"2026-08-22","data":[
                  {"model":"deepseek-v4-flash","usage":[
                    {"type":"REQUEST","amount":"5"},
                    {"type":"PROMPT_TOKEN","amount":"1000"},
                    {"type":"PROMPT_CACHE_HIT_TOKEN","amount":"200"},
                    {"type":"PROMPT_CACHE_MISS_TOKEN","amount":"800"},
                    {"type":"RESPONSE_TOKEN","amount":"400"}
                  ]}
                ]},
                {"date":"2026-08-23","data":[
                  {"model":"deepseek-v4-pro","usage":[
                    {"type":"REQUEST","amount":"3"},
                    {"type":"RESPONSE_TOKEN","amount":"250"}
                  ]},
                  {"model":"deepseek-v4-flash","usage":[
                    {"type":"REQUEST","amount":"2"},
                    {"type":"PROMPT_CACHE_HIT_TOKEN","amount":"100"}
                  ]}
                ]}
              ]
            }}}
        """.trimIndent()
        val rows = DeepSeekConsoleParser.parseMonthlyDaysByModel(json, costMode = false)
        assertNotNull(rows)
        // 3 行：8-22 flash，8-23 pro，8-23 flash
        assertEquals(3, rows!!.size)
        val d22flash = rows.first { it.date == "2026-08-22" && it.model == "deepseek-v4-flash" }
        assertEquals(5L, d22flash.requests)
        assertEquals(2000L, d22flash.inputTokens) // 1000 + 200 + 800
        assertEquals(400L, d22flash.outputTokens)
        assertEquals(2400L, d22flash.totalTokens)

        val d23pro = rows.first { it.date == "2026-08-23" && it.model == "deepseek-v4-pro" }
        assertEquals(3L, d23pro.requests)
        assertEquals(0L, d23pro.inputTokens)
        assertEquals(250L, d23pro.outputTokens)

        val d23flash = rows.first { it.date == "2026-08-23" && it.model == "deepseek-v4-flash" }
        assertEquals(2L, d23flash.requests)
        assertEquals(100L, d23flash.inputTokens)
    }

    @Test
    fun parseMonthlyDaysByModel_costMode_arrayBizDataAccumulatesMoney() {
        // 真机实测结构：cost 端点 biz_data 为数组（按币种分组），days[] 同构，amount 为金额
        val json = """
            {"code":0,"data":{"biz_code":0,"biz_data":[{
              "currency":"CNY",
              "days":[
                {"date":"2026-08-23","data":[
                  {"model":"deepseek-v4-flash","usage":[
                    {"type":"PROMPT_TOKEN","amount":"12.34"},
                    {"type":"RESPONSE_TOKEN","amount":"5.66"}
                  ]}
                ]}
              ]
            }]}}
        """.trimIndent()
        val rows = DeepSeekConsoleParser.parseMonthlyDaysByModel(json, costMode = true)
        assertNotNull(rows)
        assertEquals(1, rows!!.size)
        assertEquals(18.0, rows[0].cost, 0.001)
        assertEquals(0L, rows[0].requests)
        assertEquals(0L, rows[0].inputTokens)
    }

    @Test
    fun parseMonthlyDaysByModel_arrayBizDataGroupsMerged() {
        // 同名模型同天出现在多个分组时聚合求和
        val json = """
            {"data":{"biz_code":0,"biz_data":[
              {"days":[{"date":"2026-08-23","data":[{"model":"m1","usage":[{"type":"REQUEST","amount":"10"}]}]}]},
              {"days":[{"date":"2026-08-23","data":[{"model":"m1","usage":[{"type":"REQUEST","amount":"5"}]}]}]}
            ]}}
        """.trimIndent()
        val rows = DeepSeekConsoleParser.parseMonthlyDaysByModel(json, costMode = false)
        assertNotNull(rows)
        assertEquals(1, rows!!.size)
        assertEquals(15L, rows[0].requests)
    }

    @Test
    fun parseMonthlyDaysByModel_emptyOrGarbageReturnsNull() {
        assertNull(DeepSeekConsoleParser.parseMonthlyDaysByModel("not json", costMode = false))
        assertNull(DeepSeekConsoleParser.parseMonthlyDaysByModel("""{"data":{"biz_data":[{"days":[]}]}}""", costMode = false))
        assertNull(DeepSeekConsoleParser.parseMonthlyDaysByModel("""{"data":{"biz_data":[]}}""", costMode = false))
    }

    @Test
    fun `amount 为非数字字符串不抛异常且千分位可解析`() {
        // 旧实现的 asDouble 兜底对 "1,234.56" 抛 NumberFormatException（违反"绝不抛异常"契约），
        // "N/A" 同理导致整次控制台抓取失败
        val json = """
            {"data":{"biz_code":0,"biz_data":[{"total":[
                {"model":"deepseek-chat","usage":[{"type":"RESPONSE_TOKEN","amount":"1,234.56"}]},
                {"model":"deepseek-reasoner","usage":[{"type":"RESPONSE_TOKEN","amount":"N/A"}]}
            ]}]}}
        """.trimIndent()
        val rows = DeepSeekConsoleParser.parseMonthlyTotals(json, costMode = false)
        assertNotNull(rows)
        assertEquals(1234L, rows!!.first { it.model == "deepseek-chat" }.outputTokens)
        assertNotNull(rows.first { it.model == "deepseek-reasoner" })
    }
}
