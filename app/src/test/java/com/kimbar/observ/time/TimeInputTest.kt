package com.kimbar.observ.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class TimeInputTest {
    private val zone = ZoneId.of("Europe/Warsaw")

    private fun nanos(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int, n: Int = 0): Long {
        val i = LocalDateTime.of(y, mo, d, h, mi, s, n).atZone(zone).toInstant()
        return i.epochSecond * 1_000_000_000L + i.nano
    }

    @Test
    fun parsesFractionsOfAnyLength() {
        val near = nanos(2026, 10, 17, 14, 3, 20)
        assertEquals(nanos(2026, 10, 17, 14, 3, 22, 123_000_000), TimeInput.parse("14:03:22.123", near, zone))
        assertEquals(nanos(2026, 10, 17, 14, 3, 22, 500_000_000), TimeInput.parse("14:03:22,5", near, zone))
        assertEquals(nanos(2026, 10, 17, 14, 3, 0), TimeInput.parse("14:03", near, zone))
        assertEquals(nanos(2026, 10, 17, 9, 3, 22, 123_456_000), TimeInput.parse("9:03:22.123456", near, zone))
    }

    @Test
    fun picksNearestDayAroundMidnight() {
        val justAfterMidnight = nanos(2026, 10, 18, 0, 0, 1)
        assertEquals(nanos(2026, 10, 17, 23, 59, 59), TimeInput.parse("23:59:59", justAfterMidnight, zone))
    }

    @Test
    fun parsesExplicitDate() {
        val near = nanos(2026, 10, 17, 12, 0, 0)
        assertEquals(nanos(2026, 1, 2, 3, 4, 5, 6_000_000), TimeInput.parse("2026-01-02 03:04:05.006", near, zone))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(TimeInput.parse("hello", 0, zone))
        assertNull(TimeInput.parse("", 0, zone))
        assertNull(TimeInput.parse("25:00:00", 0, zone))
    }

    @Test
    fun formatsEpochSecondsWithMicroseconds() {
        assertEquals("1.000001", TimeKeeper.formatEpochSeconds(1_000_001_999L))
        assertEquals("+0.137 s", TimeKeeper.formatOffset(137_000_000L))
    }
}
