package com.example.watchdog.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatUtilsTest {

    // ===== formatNumber =====

    @Test
    fun formatNumber_belowThousand() {
        assertEquals("0", FormatUtils.formatNumber(0))
        assertEquals("999", FormatUtils.formatNumber(999))
    }

    @Test
    fun formatNumber_kilo() {
        assertEquals("1.0K", FormatUtils.formatNumber(1_000))
        assertEquals("5.5K", FormatUtils.formatNumber(5_500))
    }

    @Test
    fun formatNumber_mega() {
        assertEquals("1.0M", FormatUtils.formatNumber(1_000_000))
        assertEquals("1.5M", FormatUtils.formatNumber(1_500_000))
    }

    @Test
    fun formatNumber_billion() {
        assertEquals("1.0B", FormatUtils.formatNumber(1_000_000_000))
        assertEquals("2.0B", FormatUtils.formatNumber(2_000_000_000))
    }

    // ===== parseTokenNumber =====

    @Test
    fun parseTokenNumber_plainNumber() {
        assertEquals(500L, FormatUtils.parseTokenNumber("500"))
        assertEquals(0L, FormatUtils.parseTokenNumber("0"))
    }

    @Test
    fun parseTokenNumber_suffixK() {
        assertEquals(3_000L, FormatUtils.parseTokenNumber("3K"))
        assertEquals(3_000L, FormatUtils.parseTokenNumber("3k"))
    }

    @Test
    fun parseTokenNumber_suffixM() {
        assertEquals(1_200_000L, FormatUtils.parseTokenNumber("1.2M"))
    }

    @Test
    fun parseTokenNumber_suffixB() {
        assertEquals(2_000_000_000L, FormatUtils.parseTokenNumber("2B"))
    }

    @Test
    fun parseTokenNumber_invalidReturnsZero() {
        assertEquals(0L, FormatUtils.parseTokenNumber("abc"))
        assertEquals(0L, FormatUtils.parseTokenNumber(""))
    }
}
