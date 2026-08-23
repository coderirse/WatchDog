package com.example.watchdog.data.api

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

    /** 在 [text] 中找 [keywords] 任一关键词，取其之后最近的金额形如 7.56019 / 125.70595。 */
    private fun amountNear(text: String, keywords: List<String>): String? {
        for (kw in keywords) {
            val idx = text.indexOf(kw)
            if (idx < 0) continue
            val tail = text.substring(idx, (idx + 400).coerceAtMost(text.length))
            // 金额可能被 HTML 标签/引号/转义分隔，匹配数字串（可带小数点、逗号）
            val m = Regex("""\d{1,3}(?:,\d{3})*(?:\.\d{1,6})""").find(tail)
            if (m != null) return m.value
        }
        return null
    }

    /** 还原常见 HTML/JSON 转义与 \uXXXX。 */
    private fun unescape(s: String): String {
        var r = s
        r = r.replace("\\u4f59", "余").replace("\\u989d", "额")
            .replace("\\u4eca", "今").replace("\\u65e5", "日")
            .replace("\\u6d88", "消").replace("\\u8d39", "费")
            .replace("\\u672c", "本").replace("\\u6708", "月")
            .replace("\\u603b", "总")
            .replace("\\\"", "\"").replace("\\/", "/").replace("&nbsp;", " ")
            .replace("&#165;", "¥").replace("&yen;", "¥")
        return r
    }
}
