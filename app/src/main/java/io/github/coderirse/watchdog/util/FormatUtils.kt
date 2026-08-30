package io.github.coderirse.watchdog.util

import java.util.Locale

/**
 * 数字 / Token 格式化纯函数集合。
 * 从 QuotaRepository 与 PlatformQuotaCard 中抽离，消除重复实现并便于单元测试。
 */
object FormatUtils {

    /** 大数值缩写：1.2B / 3.4M / 5.6K，小于 1000 原样输出。 */
    fun formatNumber(count: Long): String = when {
        count >= 1_000_000_000 -> String.format(Locale.US, "%.1fB", count / 1_000_000_000.0)
        count >= 1_000_000 -> String.format(Locale.US, "%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format(Locale.US, "%.1fK", count / 1_000.0)
        else -> count.toString()
    }

    /** 解析带 B/M/K 后缀的 Token 数量字符串为 Long；无法解析时返回 0。 */
    fun parseTokenNumber(f: String): Long = try {
        when {
            f.endsWith("B", true) -> (f.dropLast(1).toDouble() * 1_000_000_000).toLong()
            f.endsWith("M", true) -> (f.dropLast(1).toDouble() * 1_000_000).toLong()
            f.endsWith("K", true) -> (f.dropLast(1).toDouble() * 1_000).toLong()
            else -> f.toLongOrNull() ?: 0L
        }
    } catch (_: Exception) { 0L }
}
