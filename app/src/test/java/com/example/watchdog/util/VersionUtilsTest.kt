package com.example.watchdog.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionUtilsTest {

    @Test
    fun `newer minor version is detected`() {
        assertTrue(VersionUtils.isNewer("1.1.0", "1.0.4"))
    }

    @Test
    fun `equal version is not newer`() {
        assertFalse(VersionUtils.isNewer("1.0.4", "1.0.4"))
    }

    @Test
    fun `older version is not newer`() {
        assertFalse(VersionUtils.isNewer("1.0.3", "1.0.4"))
    }

    @Test
    fun `multi-digit segment compares numerically`() {
        assertTrue(VersionUtils.isNewer("1.10", "1.9"))
    }

    @Test
    fun `suffix non-numeric part is treated as zero`() {
        assertFalse(VersionUtils.isNewer("1.0.4-beta", "1.0.4"))
    }
}
