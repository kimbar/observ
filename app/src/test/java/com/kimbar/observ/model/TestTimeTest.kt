package com.kimbar.observ.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TestTimeTest {
    private val s = 1_000_000_000L
    private val boot = 7
    private val epoch0 = 1_792_000_000L * s

    /** A moment [counterS] seconds after boot on boot 7, phone clock consistent with it. */
    private fun m(counterNs: Long, b: Int = boot, wallSkewNs: Long = 0) = Moment(counterNs, b, epoch0 + counterNs + wallSkewNs)

    private fun base() = TestData("t", epoch0)

    private fun item(id: String, type: ItemType, at: Moment, end: Moment? = null) = Item(id, type, at, end)

    @Test
    fun notSyncedUntilFirstActiveSync() {
        var t = TestOps.addItem(base(), item("a", ItemType.MARK, m(100 * s)))
        assertNull(TestTime(t).testTimeNs(m(100 * s)))
        t = TestOps.addSync(t, Sync("s1", "a", readingS = 10))
        assertEquals(10 * s, TestTime(t).testTimeNs(m(100 * s)))
        t = TestOps.setSyncActive(t, "s1", false)
        assertNull(TestTime(t).testTimeNs(m(100 * s)))
    }

    @Test
    fun appliesRetroactivelyAndGoesNegative() {
        var t = TestOps.addItem(base(), item("early", ItemType.NOTE, m(10 * s)))
        t = TestOps.addItem(t, item("mk", ItemType.MARK, m(200 * s)))
        t = TestOps.addSync(t, Sync("s1", "mk", readingS = 60)) // 200 s on phone = 1:00 test time
        val tt = TestTime(t)
        assertEquals(-130 * s, tt.testTimeNs(m(10 * s)))
        assertEquals("-002:10.0", Fmt.testTime(tt.testTimeNs(m(10 * s))!!))
    }

    @Test
    fun averagesActiveSyncsAndReportsDeltas() {
        var t = base()
        t = TestOps.addItem(t, item("a", ItemType.MARK, m(100 * s)))
        t = TestOps.addItem(t, item("b", ItemType.MARK, m(200 * s)))
        t = TestOps.addItem(t, item("c", ItemType.MARK, m(300 * s)))
        // readings disagree: offsets 90, 89.?, 95 s -> outlier c
        t = TestOps.addSync(t, Sync("sa", "a", 10)) // offset 90
        t = TestOps.addSync(t, Sync("sb", "b", 111)) // offset 89
        t = TestOps.addSync(t, Sync("sc", "c", 205)) // offset 95
        var tt = TestTime(t)
        assertEquals((90 + 89 + 95) * s / 3, tt.averageOffsetNs!! - epoch0)
        // deactivate outlier
        t = TestOps.setSyncActive(t, "sc", false)
        tt = TestTime(t)
        assertEquals(89_500_000_000L, tt.averageOffsetNs!! - epoch0)
        assertEquals(500_000_000L, tt.deltaNs(t.syncs[1])) // b: reading 111 vs computed 110.5
        assertEquals(-500_000_000L, tt.deltaNs(t.syncs[0]))
        assertEquals(500_000_000L, tt.spreadNs)
        // inactive sync still gets a delta for display
        assertEquals(205 * s - (300 * s - 89_500_000_000L), tt.deltaNs(t.syncs[2]))
    }

    @Test
    fun photoSyncsAddHalfASecond() {
        var t = TestOps.addItem(base(), item("p", ItemType.PHOTO, m(100 * s)))
        t = TestOps.addSync(t, Sync("s", "p", 50))
        assertEquals(50 * s + 500_000_000L, TestTime(t).testTimeNs(m(100 * s)))
        assertEquals(50, TestTime(t).suggestedReadingS(m(100 * s), PHOTO_ADJUSTMENT_NS))
    }

    @Test
    fun videoFramesAreSeparateSyncMoments() {
        var t = TestOps.addItem(base(), item("v", ItemType.VIDEO, m(100 * s), m(160 * s)))
        t = TestOps.addSync(t, Sync("f1", "v", 20, frame = 30, frameOffsetNs = 1 * s))
        t = TestOps.addSync(t, Sync("f2", "v", 50, frame = 930, frameOffsetNs = 31 * s))
        val tt = TestTime(t)
        assertEquals(0L, tt.deltaNs(t.syncs[0]))
        assertEquals(0L, tt.deltaNs(t.syncs[1]))
        assertEquals(19 * s, tt.testTimeNs(m(100 * s)))
    }

    @Test
    fun inactiveSourceItemDoesNotCount() {
        var t = TestOps.addItem(base(), item("a", ItemType.MARK, m(100 * s)))
        t = TestOps.addSync(t, Sync("s", "a", 10))
        t = TestOps.setItemActive(t, "a", false)
        assertFalse(TestTime(t).isSynced)
    }

    @Test
    fun rebootIsBridgedByWallClock() {
        var t = TestOps.addItem(base(), item("a", ItemType.MARK, m(1000 * s)))
        t = TestOps.addSync(t, Sync("s", "a", 0))
        // reboot: counter restarts; wall clock continues 500 s later
        val after = Moment(5 * s, boot + 1, epoch0 + 1500 * s)
        t = TestOps.addItem(t, item("b", ItemType.MARK, after))
        val tt = TestTime(t)
        assertEquals(500 * s, tt.testTimeNs(after))
        assertTrue(tt.afterReboot(after))
        assertFalse(tt.afterReboot(m(1000 * s)))
    }

    @Test
    fun nineHourLimitOnlyOnceSynced() {
        var t = TestOps.addItem(base(), item("a", ItemType.MARK, m(100 * s)))
        assertTrue(TestTime(t).canAddAt(m(100_000 * s)))
        t = TestOps.addSync(t, Sync("s", "a", 0))
        assertTrue(TestTime(t).canAddAt(m(100 * s + TEST_LIMIT_NS - 1)))
        assertFalse(TestTime(t).canAddAt(m(100 * s + TEST_LIMIT_NS)))
    }

    @Test
    fun exportNamingAndDeleteRules() {
        var t = TestData("Run 1/a", epoch0)
        assertEquals("Run 1_a.zip", TestOps.exportFileName(t))
        assertFalse(TestOps.canDelete(t))
        t = TestOps.markExported(t)
        assertTrue(TestOps.canDelete(t))
        assertEquals("Run 1_a_v.2.zip", TestOps.exportFileName(t))
        t = TestOps.addItem(t, item("a", ItemType.MARK, m(1)))
        assertFalse(TestOps.canDelete(t))
        t = TestOps.setTestActive(t, false)
        assertTrue(TestOps.canDelete(t))
        assertFalse(TestOps.canExport(t))
    }
}
