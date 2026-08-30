package io.github.coderirse.watchdog.data.api

import com.google.gson.Gson
import com.google.gson.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KimiCodeParserTest {

    private val gson = Gson()
    private fun el(json: String): JsonElement = gson.fromJson(json, JsonElement::class.java)

    // ===== parsePlanName =====

    @Test
    fun parsePlanName_primitiveString() {
        assertEquals("Pro", KimiCodeParser.parsePlanName(el("\"Pro\"")))
    }

    @Test
    fun parsePlanName_objectWithName() {
        assertEquals("Pro", KimiCodeParser.parsePlanName(el("""{"name":"Pro"}""")))
    }

    @Test
    fun parsePlanName_nullOrJsonNull() {
        assertNull(KimiCodeParser.parsePlanName(null))
        assertNull(KimiCodeParser.parsePlanName(el("null")))
    }

    // ===== parseWindow =====

    @Test
    fun parseWindow_fullFields() {
        val w = KimiCodeQuotaWindow(
            name = el("\"5h\""),
            used = el("42"),
            remaining = el("58"),
            limit = el("100"),
            resetTime = el("1700000000"),
            expiresAt = el("1700000000000")
        )
        val qw = KimiCodeParser.parseWindow(w)
        assertEquals("5小时", qw.name)
        assertEquals(42.0, qw.used!!, 0.0001)
        assertEquals(58.0, qw.remaining!!, 0.0001)
        assertEquals(100.0, qw.limit!!, 0.0001)
        assertEquals(1700000000000L, qw.resetTime!!)
        assertEquals(1700000000000L, qw.expiresAt!!)
    }

    @Test
    fun parseWindow_derivesRemainingFromUsedAndLimit() {
        val w = KimiCodeQuotaWindow(used = el("42"), limit = el("100"))
        val qw = KimiCodeParser.parseWindow(w)
        assertEquals(58.0, qw.remaining!!, 0.0001)
    }

    @Test
    fun parseWindow_nullFieldsStayNull() {
        val qw = KimiCodeParser.parseWindow(KimiCodeQuotaWindow())
        assertNull(qw.name)
        assertNull(qw.used)
        assertNull(qw.remaining)
        assertNull(qw.limit)
        assertNull(qw.resetTime)
        assertNull(qw.expiresAt)
    }

    // ===== extractWindowName =====

    @Test
    fun extractWindowName_primitiveString() {
        assertEquals("week", KimiCodeParser.extractWindowName(el("\"week\"")))
    }

    @Test
    fun extractWindowName_objectWithTypeKey() {
        assertEquals("周", KimiCodeParser.extractWindowName(el("""{"type":"周"}""")))
    }

    @Test
    fun extractWindowName_objectWithSeconds() {
        assertEquals("5小时", KimiCodeParser.extractWindowName(el("""{"seconds":18000}""")))
    }

    @Test
    fun extractWindowName_nullOrJsonNull() {
        assertNull(KimiCodeParser.extractWindowName(null))
        assertNull(KimiCodeParser.extractWindowName(el("null")))
    }

    // ===== normalizeWindowName =====

    @Test
    fun normalizeWindowName_fiveHours() {
        assertEquals("5小时", KimiCodeParser.normalizeWindowName("5 hours"))
        assertEquals("5小时", KimiCodeParser.normalizeWindowName("5h"))
        assertEquals("5小时", KimiCodeParser.normalizeWindowName("5小时"))
    }

    @Test
    fun normalizeWindowName_week() {
        assertEquals("周", KimiCodeParser.normalizeWindowName("weekly"))
        assertEquals("周", KimiCodeParser.normalizeWindowName("Week"))
    }

    @Test
    fun normalizeWindowName_month() {
        assertEquals("月", KimiCodeParser.normalizeWindowName("monthly"))
        assertEquals("月", KimiCodeParser.normalizeWindowName("Month"))
    }

    @Test
    fun normalizeWindowName_unrecognizedKeepsOriginal() {
        assertEquals("自定义", KimiCodeParser.normalizeWindowName("自定义"))
    }

    @Test
    fun normalizeWindowName_blankOrNull() {
        assertNull(KimiCodeParser.normalizeWindowName(null))
        assertNull(KimiCodeParser.normalizeWindowName("  "))
    }

    // ===== parseBooster =====

    @Test
    fun parseBooster_primitiveString() {
        assertEquals("booster", KimiCodeParser.parseBooster(el("\"booster\"")))
    }

    @Test
    fun parseBooster_objectWithDescription() {
        assertEquals("desc", KimiCodeParser.parseBooster(el("""{"description":"desc"}""")))
    }

    @Test
    fun parseBooster_arrayTakesFirst() {
        assertEquals("first", KimiCodeParser.parseBooster(el("""["first","second"]""")))
    }

    @Test
    fun parseBooster_nullOrJsonNull() {
        assertNull(KimiCodeParser.parseBooster(null))
        assertNull(KimiCodeParser.parseBooster(el("null")))
    }

    // ===== toDoubleOrNull =====

    @Test
    fun toDoubleOrNull_number() {
        assertEquals(42.5, KimiCodeParser.toDoubleOrNull(el("42.5"))!!, 0.0001)
    }

    @Test
    fun toDoubleOrNull_numericString() {
        assertEquals(42.5, KimiCodeParser.toDoubleOrNull(el("\"42.5\""))!!, 0.0001)
    }

    @Test
    fun toDoubleOrNull_invalidReturnsNull() {
        assertNull(KimiCodeParser.toDoubleOrNull(el("\"abc\"")))
        assertNull(KimiCodeParser.toDoubleOrNull(null))
        assertNull(KimiCodeParser.toDoubleOrNull(el("null")))
    }

    // ===== toEpochMillisOrNull =====

    @Test
    fun toEpochMillisOrNull_epochSeconds() {
        assertEquals(1700000000000L, KimiCodeParser.toEpochMillisOrNull(el("1700000000")))
    }

    @Test
    fun toEpochMillisOrNull_epochMillis() {
        assertEquals(1700000000000L, KimiCodeParser.toEpochMillisOrNull(el("1700000000000")))
    }

    @Test
    fun toEpochMillisOrNull_secondsAsString() {
        assertEquals(1700000000000L, KimiCodeParser.toEpochMillisOrNull(el("\"1700000000\"")))
    }

    @Test
    fun toEpochMillisOrNull_invalidReturnsNull() {
        assertNull(KimiCodeParser.toEpochMillisOrNull(el("\"not-a-date\"")))
        assertNull(KimiCodeParser.toEpochMillisOrNull(null))
    }

    // ===== parseIso8601 =====

    @Test
    fun parseIso8601_utcZulu() {
        // 2024-01-01T00:00:00Z = 1704067200000 epoch millis
        assertEquals(1704067200000L, KimiCodeParser.parseIso8601("2024-01-01T00:00:00Z"))
    }

    @Test
    fun parseIso8601_invalidReturnsNull() {
        assertNull(KimiCodeParser.parseIso8601("not a date"))
        assertNull(KimiCodeParser.parseIso8601(""))
    }
}
