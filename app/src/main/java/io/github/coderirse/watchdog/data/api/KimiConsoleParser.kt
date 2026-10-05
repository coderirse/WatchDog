package io.github.coderirse.watchdog.data.api

/**
 * Kimi 控制台 SSR HTML 解析（方案乙最后一轮）。
 *
 * Kimi 控制台数据由 Next.js 服务端渲染，金额（余额/今日消费/本月消费/总消费）内嵌在
 * SSR HTML 中。本解析器用正则从 HTML 里宽容地提取含"金额"的文本行，定位数据。
 *
 * 说明：Next.js 流式数据（self.__next_f.push）中的中文/金额通常以转义形式存在，
 * 且页面为登录后才能渲染金额（未登录 SSR 无金额）。因此需要带登录 Cookie 请求。
 * 解析失败返回 null，由调用方回退"会话已连接"占位。
 */
object KimiConsoleParser {

    data class Result(
        val balance: String?,
        val todayCost: String?,
        val monthCost: String?,
        val totalCost: String?
    )

    /**
     * 从 SSR HTML 中提取金额。
     * 策略：按标签名（余额/今日消费/本月消费/总消费）就近匹配其后出现的第一个金额，
     * 兼容金额与标签分离、HTML 转义、JSON 转义等形态。宽容匹配，失败返回 null。
     */
    fun parse(html: String?): Result? {
        if (html.isNullOrBlank()) return null
        // 去归一化：把 \u 转义与 HTML 实体还原为明文，方便中文标签匹配
        val text = unescape(html)
        return Result(
            balance = amountNear(text, listOf("余额", "可提现余额", "账户余额")),
            todayCost = amountNear(text, listOf("今日消费", "今日消耗")),
            monthCost = amountNear(text, listOf("本月消费", "本月消耗", "本月已用")),
            totalCost = amountNear(text, listOf("总消费", "累计消费", "总消耗"))
        ).takeIf { it.balance != null || it.todayCost != null || it.monthCost != null || it.totalCost != null }
    }

    // 货币符号锚定：¥/￥ 后跟整数或带千分位的金额（如 ¥125、¥1,234.56），置信度最高
    private val CURRENCY_AMOUNT = Regex("""[¥￥]\s*(\d{1,3}(?:,\d{3})*(?:\.\d{1,6})?|\d+)""")
    // 带小数点的金额：前后不得紧邻字母/数字/点，排除日期(2026.10.02)、版本号(kimi-k2.5)等
    private val DECIMAL_AMOUNT = Regex("""(?<![0-9A-Za-z.])\d{1,3}(?:,\d{3})*\.\d{1,6}(?![0-9A-Za-z.])""")
    // 千分位整数（如 1,235）：无小数点但分组逗号是强金额特征
    private val GROUPED_AMOUNT = Regex("""(?<![0-9A-Za-z.,])\d{1,3}(?:,\d{3})+(?![0-9A-Za-z.,])""")

    /**
     * 在 [text] 中找 [keywords] 任一关键词，取其后 400 字符内的金额。
     * 三层置信度递减：货币符号锚定 → 带小数点数字 → 千分位整数。
     * 旧实现的盲窗裸匹配会把日期 "2026.10.02" 匹配成 "026.10"、模型名 "kimi-k2.5"
     * 匹配成 "2.5"，整数金额 "¥125" 则因强制要求小数点而漏采。
     */
    private fun amountNear(text: String, keywords: List<String>): String? {
        for (kw in keywords) {
            val idx = text.indexOf(kw)
            if (idx < 0) continue
            val tail = text.substring(idx, (idx + 400).coerceAtMost(text.length))
            CURRENCY_AMOUNT.find(tail)?.let { return it.groupValues[1] }
            DECIMAL_AMOUNT.find(tail)?.let { return it.value }
            GROUPED_AMOUNT.find(tail)?.let { return it.value }
        }
        return null
    }

    private val UNICODE_ESCAPE = Regex("""\\u([0-9a-fA-F]{4})""")

    /** 还原常见 HTML/JSON 转义与全部 \uXXXX（旧表只覆盖 9 个字，其余标签在转义负载下永远失配）。 */
    private fun unescape(s: String): String {
        var r = UNICODE_ESCAPE.replace(s) { m -> m.groupValues[1].toInt(16).toChar().toString() }
        r = r.replace("\\\"", "\"").replace("\\/", "/").replace("&nbsp;", " ")
            .replace("&#165;", "¥").replace("&yen;", "¥")
        return r
    }
}
