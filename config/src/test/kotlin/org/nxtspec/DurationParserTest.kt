package org.nxtspec

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class DurationParserTest {

    // === Valid Duration Tests ===

    @Test
    fun `should parse days duration`() {
        assertEquals(7.days, DurationParser.parse("7d"))
    }

    @Test
    fun `should parse hours duration`() {
        assertEquals(24.hours, DurationParser.parse("24h"))
    }

    @Test
    fun `should parse minutes duration`() {
        assertEquals(30.minutes, DurationParser.parse("30m"))
    }

    @Test
    fun `should parse seconds duration`() {
        assertEquals(60.seconds, DurationParser.parse("60s"))
    }

    @Test
    fun `should parse large days value`() {
        assertEquals(365.days, DurationParser.parse("365d"))
    }

    @Test
    fun `should parse large hours value`() {
        assertEquals(1000.hours, DurationParser.parse("1000h"))
    }

    @Test
    fun `should parse zero duration`() {
        assertEquals(0.seconds, DurationParser.parse("0s"))
        assertEquals(0.minutes, DurationParser.parse("0m"))
        assertEquals(0.hours, DurationParser.parse("0h"))
        assertEquals(0.days, DurationParser.parse("0d"))
    }

    @Test
    fun `should parse single digit duration`() {
        assertEquals(1.days, DurationParser.parse("1d"))
        assertEquals(1.hours, DurationParser.parse("1h"))
        assertEquals(1.minutes, DurationParser.parse("1m"))
        assertEquals(1.seconds, DurationParser.parse("1s"))
    }

    // === Invalid Duration Tests ===

    @Test
    fun `should fail when duration is empty`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            DurationParser.parse("")
        }
        assertContains(exception.message!!, "Invalid duration format")
    }

    @Test
    fun `should fail when duration has single character`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            DurationParser.parse("d")
        }
        assertContains(exception.message!!, "Invalid duration format")
    }

    @Test
    fun `should fail when duration has no suffix`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            DurationParser.parse("7")
        }
        assertContains(exception.message!!, "Invalid duration format")
    }

    @Test
    fun `should fail when suffix is invalid`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            DurationParser.parse("7x")
        }
        assertContains(exception.message!!, "Supported suffixes")
    }

    @Test
    fun `should fail when number is missing`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            DurationParser.parse("d7")
        }
        assertContains(exception.message!!, "not a valid number")
    }

    @Test
    fun `should fail when duration is text`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            DurationParser.parse("invalid")
        }
        assertContains(exception.message!!, "not a valid number")
    }

    @Test
    fun `should fail when duration has negative value`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            DurationParser.parse("-5d")
        }
        assertContains(exception.message!!, "non-negative")
    }

    @Test
    fun `should fail when suffix has wrong case`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            DurationParser.parse("7D")
        }
        assertContains(exception.message!!, "Supported suffixes")
    }

    @Test
    fun `should fail when duration has spaces`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            DurationParser.parse("7 d")
        }
        assertContains(exception.message!!, "not a valid number")
    }

    @Test
    fun `should fail when duration has decimal`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            DurationParser.parse("7.5d")
        }
        assertContains(exception.message!!, "not a valid number")
    }
}
