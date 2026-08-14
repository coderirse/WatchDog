package com.example.watchdog.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeModeTest {

    @Test
    fun fromString_light() {
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromString("light"))
    }

    @Test
    fun fromString_dark() {
        assertEquals(ThemeMode.DARK, ThemeMode.fromString("dark"))
    }

    @Test
    fun fromString_system() {
        assertEquals(ThemeMode.FOLLOW_SYSTEM, ThemeMode.fromString("system"))
    }

    @Test
    fun fromString_nullFallsBackToSystem() {
        assertEquals(ThemeMode.FOLLOW_SYSTEM, ThemeMode.fromString(null))
    }

    @Test
    fun fromString_invalidFallsBackToSystem() {
        assertEquals(ThemeMode.FOLLOW_SYSTEM, ThemeMode.fromString("invalid"))
        assertEquals(ThemeMode.FOLLOW_SYSTEM, ThemeMode.fromString(""))
    }
}
