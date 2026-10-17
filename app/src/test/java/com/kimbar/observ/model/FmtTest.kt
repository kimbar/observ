package com.kimbar.observ.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FmtTest {
    private val s = 1_000_000_000L

    @Test
    fun formatA() {
        assertEquals("000:00.0", Fmt.testTime(0))
        assertEquals("123:12.6", Fmt.testTime(123 * 60 * s + 12 * s + 560_000_000L))
        assertEquals("007:05.3", Fmt.testTime(425 * s + 340_000_000L))
        assertEquals("-001:23.4", Fmt.testTime(-(83 * s + 400_000_000L)))
        assertEquals("000:00.0", Fmt.testTime(-40_000_000L)) // rounds to zero, no sign
        assertEquals("000:01.0", Fmt.testTime(950_000_000L)) // half up
    }

    @Test
    fun formatB() {
        assertEquals("0:07:05.3", Fmt.testTime(425 * s + 300_000_000L, TimeFormat.B))
        assertEquals("2:03:12.6", Fmt.testTime((2 * 3600 + 192) * s + 600_000_000L, TimeFormat.B))
        assertEquals("-0:01:23.4", Fmt.testTime(-(83 * s + 400_000_000L), TimeFormat.B))
    }

    @Test
    fun readingsAndDeltas() {
        assertEquals("007:05", Fmt.reading(425))
        assertEquals("0:07:05", Fmt.reading(425, TimeFormat.B))
        assertEquals("+0.3 s", Fmt.delta(260_000_000L))
        assertEquals("-1.2 s", Fmt.delta(-1_210_000_000L))
        assertEquals("83.412", Fmt.seconds3(83_411_700_000L))
        assertEquals("1792238602.123456", Fmt.epoch(1_792_238_602_123_456_789L))
    }

    @Test
    fun wheelsRoundTrip() {
        for (sec in listOf(0, 59, 60, 425, 3599, 3600, 7392, 9 * 3600 - 1)) {
            for (f in TimeFormat.entries) {
                assertEquals("$sec $f", sec, Wheels.seconds(Wheels.digits(sec, f), f))
            }
        }
        assertEquals(listOf(0, 0, 7, 0, 5), Wheels.digits(425, TimeFormat.A))
        assertEquals(listOf(2, 0, 3, 1, 2), Wheels.digits(2 * 3600 + 3 * 60 + 12, TimeFormat.B))
        assertEquals(listOf(1, 2, 3, 1, 2), Wheels.digits(123 * 60 + 12, TimeFormat.A))
    }
}
