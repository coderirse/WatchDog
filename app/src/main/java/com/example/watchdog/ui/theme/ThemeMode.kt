package com.example.watchdog.ui.theme

/**
 * 应用主题模式。存储层以字符串形式持久化（"system"/"light"/"dark"），
 * 通过 [fromString] 还原。
 */
enum class ThemeMode {
    FOLLOW_SYSTEM,
    LIGHT,
    DARK;

    companion object {
        fun fromString(value: String?): ThemeMode = when (value) {
            "light" -> LIGHT
            "dark" -> DARK
            else -> FOLLOW_SYSTEM
        }
    }
}
