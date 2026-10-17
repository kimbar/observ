package com.kimbar.observ.model

import kotlin.math.abs

private const val SECOND = 1_000_000_000L

/** Photos catch a whole-second display at a random point within the second (DESIGN.md 3). */
const val PHOTO_ADJUSTMENT_NS = 500_000_000L

/** No new items once the synced test time reaches this (DESIGN.md 5). */
const val TEST_LIMIT_NS = 9 * 3600 * SECOND

/** Computes test times for one Test from its active syncs. Pure; recreate after each change. */
class TestTime(val test: TestData) {

    private val itemsById = test.items.associateBy { it.id }

    /**
     * One timeline for the whole Test: the counter of the moment's boot plus that boot's base.
     * Within one boot this is exactly the monotonic counter; across a reboot it is bridged by
     * the wall clock.
     */
    fun timeline(m: Moment): Long = m.counterNs + (test.bootBases[m.boot] ?: (m.epochNs - m.counterNs))

    fun afterReboot(m: Moment): Boolean = test.firstBoot != null && m.boot != test.firstBoot

    fun item(id: String): Item? = itemsById[id]

    /** The phone moment a sync refers to, or null if its source item is missing. */
    fun moment(s: Sync): Moment? {
        val item = itemsById[s.itemId] ?: return null
        return if (item.type == ItemType.VIDEO) item.moment.plus(s.frameOffsetNs) else item.moment
    }

    fun adjustmentNs(s: Sync): Long = if (itemsById[s.itemId]?.type == ItemType.PHOTO) PHOTO_ADJUSTMENT_NS else 0

    /** What the external clock really showed at the sync's moment, in test-time ns. */
    fun referenceNs(s: Sync): Long = s.readingS * SECOND + adjustmentNs(s)

    private fun offset(s: Sync): Long? = moment(s)?.let { timeline(it) - referenceNs(s) }

    /** A sync counts only if it and its source item are both active. */
    fun counts(s: Sync): Boolean = s.active && itemsById[s.itemId]?.active == true

    val activeSyncs: List<Sync> = test.syncs.filter { counts(it) && moment(it) != null }

    /** Average offset (timeline − test time), or null when the Test is not synced. */
    val averageOffsetNs: Long? = activeSyncs.mapNotNull { offset(it) }.let { offs ->
        if (offs.isEmpty()) null else averageOf(offs)
    }

    val isSynced: Boolean get() = averageOffsetNs != null

    fun testTimeNs(m: Moment): Long? = averageOffsetNs?.let { timeline(m) - it }

    /** reference − computed test time: how far a sync is from the consensus. */
    fun deltaNs(s: Sync): Long? {
        val m = moment(s) ?: return null
        val t = testTimeNs(m) ?: return null
        return referenceNs(s) - t
    }

    /** Largest |delta| among active syncs. */
    val spreadNs: Long? = activeSyncs.mapNotNull { deltaNs(it) }.maxOfOrNull { abs(it) }

    fun canAddAt(now: Moment): Boolean {
        val t = testTimeNs(now) ?: return true
        return t < TEST_LIMIT_NS
    }

    /** Starting value for the reading wheels: the estimate for that moment, whole seconds. */
    fun suggestedReadingS(m: Moment, adjustmentNs: Long): Int {
        val t = testTimeNs(m) ?: return 0
        return Math.floorDiv(t - adjustmentNs + SECOND / 2, SECOND).toInt().coerceIn(0, MAX_READING_S)
    }

    companion object {
        /** 999:59 in format A; format B (9:59:59) is larger, so this is the common limit. */
        const val MAX_READING_S = 999 * 60 + 59

        /** Mean of large Longs without overflow. */
        fun averageOf(values: List<Long>): Long {
            val base = values.first()
            val sum = values.fold(0L) { acc, v -> acc + (v - base) }
            return base + Math.floorDiv(sum + values.size / 2, values.size.toLong())
        }
    }
}
