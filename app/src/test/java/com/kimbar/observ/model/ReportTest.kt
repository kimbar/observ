package com.kimbar.observ.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ReportTest {
    private val s = 1_000_000_000L
    private val zone = ZoneId.of("UTC")

    @Test
    fun eventsAndSyncsFiles() {
        val e0 = 1_792_000_000L * s
        fun m(c: Long) = Moment(c, 1, e0 + c)
        var t = TestData("demo", e0)
        t = TestOps.addItem(t, Item("n", ItemType.NOTE, m(5 * s), text = "line1\nline2\ttab"))
        t = TestOps.addItem(t, Item("v", ItemType.VIDEO, m(10 * s), m(70 * s + 412_000_000L), file = "v.mp4"))
        t = TestOps.addSync(t, Sync("s", "v", 120, frame = 59, frameOffsetNs = 2 * s))
        val ev = Report.events(t, "dev", zone).lines()
        val header = ev.indexOfFirst { it.startsWith("test_time") }
        val note = ev[header + 1].split('\t')
        assertEquals("001:53.0", note[0])
        assertEquals("NOTE", note[3])
        assertEquals("line1\\nline2\\ttab", note[8])
        val video = ev[header + 2].split('\t')
        assertEquals("001:58.0", video[0])
        assertEquals("002:58.4", video[5])
        assertEquals("60.412", video[6])
        val sy = Report.syncs(t, "dev", zone).lines()
        val row = sy[sy.indexOfFirst { it.startsWith("test_time") } + 1].split('\t')
        assertEquals(listOf("002:00.0", "002:00", "+0.000", "+0.0", "VIDEO", "v.mp4", "60", "1"), row.take(8))
        assertTrue(sy[0].startsWith("# observ test: demo"))
    }
}
