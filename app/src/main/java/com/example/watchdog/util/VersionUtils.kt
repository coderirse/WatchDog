package com.example.watchdog.util

/**
 * 纯数字语义化版本号比较工具（如 1.0.4 / 1.10）。
 * 非数字段按 0 处理，保证 "1.0.4-beta" 这类版本不会异常。
 */
object VersionUtils {
    fun isNewer(latest: String, current: String): Boolean {
        val l = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val c = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(l.size, c.size)) {
            val lv = l.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (lv > cv) return true
            if (lv < cv) return false
        }
        return false
    }
}
