package com.kimbar.observ.time

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The app's single source of time.
 *
 * Every event is captured as [SystemClock.elapsedRealtimeNanos] (monotonic, never jumps,
 * keeps counting in deep sleep) and only converted to wall time through [bootEpochNanos]:
 *
 *     referenceEpochNanos = elapsedRealtimeNanos + bootEpochNanos
 *
 * A sync against an external clock just replaces [bootEpochNanos]. Because the raw elapsed
 * value is also written to the session log, events can be re-corrected afterwards.
 * The value is only valid within one boot, so it is discarded after a reboot.
 */
class TimeKeeper(context: Context) {

    data class Sync(
        val bootEpochNanos: Long,
        val method: String,
        /** elapsedRealtimeNanos at the moment of the reference observation. */
        val atElapsedNanos: Long,
    )

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("timekeeper", Context.MODE_PRIVATE)

    var current: Sync? = load()
        private set

    /** Previous sync in the same boot, used to estimate the phone's clock drift. */
    var previous: Sync? = loadPrevious()
        private set

    val isSynced: Boolean get() = current != null

    /** Unsynced fallback: the phone's own wall clock (millisecond resolution). */
    private fun systemBootEpochNanos(): Long =
        System.currentTimeMillis() * 1_000_000L - SystemClock.elapsedRealtimeNanos()

    fun bootEpochNanos(): Long = current?.bootEpochNanos ?: systemBootEpochNanos()

    fun nowElapsed(): Long = SystemClock.elapsedRealtimeNanos()

    fun toEpochNanos(elapsedNanos: Long): Long = elapsedNanos + bootEpochNanos()

    fun nowEpochNanos(): Long = toEpochNanos(nowElapsed())

    /** Offset of the reference clock relative to the phone's own clock, in nanoseconds. */
    fun offsetFromSystemNanos(): Long = bootEpochNanos() - systemBootEpochNanos()

    /**
     * Records that at [elapsedNanos] the external reference showed [referenceEpochNanos].
     * Returns the change in nanoseconds applied relative to the previous time base.
     */
    fun applySync(elapsedNanos: Long, referenceEpochNanos: Long, method: String): Long {
        val newBase = referenceEpochNanos - elapsedNanos
        val change = newBase - bootEpochNanos()
        previous = current
        current = Sync(newBase, method, elapsedNanos)
        save()
        return change
    }

    fun clearSync() {
        previous = null
        current = null
        prefs.edit().clear().apply()
    }

    /** Drift of the phone clock vs. the reference in parts per million, if two syncs exist. */
    fun driftPpm(): Double? {
        val a = previous ?: return null
        val b = current ?: return null
        val span = b.atElapsedNanos - a.atElapsedNanos
        if (span < 60_000_000_000L) return null // under a minute apart: too noisy
        return (b.bootEpochNanos - a.bootEpochNanos).toDouble() / span * 1e6
    }

    private fun bootCount(): Int =
        Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT, -1)

    private fun load(): Sync? = loadSync("cur")
    private fun loadPrevious(): Sync? = loadSync("prev")

    private fun loadSync(prefix: String): Sync? {
        if (prefs.getInt("bootCount", -2) != bootCount()) return null
        if (!prefs.contains("${prefix}_base")) return null
        return Sync(
            prefs.getLong("${prefix}_base", 0),
            prefs.getString("${prefix}_method", "") ?: "",
            prefs.getLong("${prefix}_at", 0),
        )
    }

    private fun save() {
        val e = prefs.edit().clear().putInt("bootCount", bootCount())
        current?.let { e.putLong("cur_base", it.bootEpochNanos).putString("cur_method", it.method).putLong("cur_at", it.atElapsedNanos) }
        previous?.let { e.putLong("prev_base", it.bootEpochNanos).putString("prev_method", it.method).putLong("prev_at", it.atElapsedNanos) }
        e.apply()
    }

    companion object {
        val zone: ZoneId get() = ZoneId.systemDefault()
        private val clockFmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        private val fullFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS")

        fun instant(epochNanos: Long): Instant =
            Instant.ofEpochSecond(Math.floorDiv(epochNanos, 1_000_000_000L), Math.floorMod(epochNanos, 1_000_000_000L))

        fun formatClock(epochNanos: Long): String = clockFmt.format(instant(epochNanos).atZone(zone))
        fun formatFull(epochNanos: Long): String = fullFmt.format(instant(epochNanos).atZone(zone))

        /** Seconds since the Unix epoch with microsecond digits, e.g. 1792330000.123456 */
        fun formatEpochSeconds(epochNanos: Long): String {
            val s = Math.floorDiv(epochNanos, 1_000_000_000L)
            val us = Math.floorMod(epochNanos, 1_000_000_000L) / 1000
            return "%d.%06d".format(s, us)
        }

        /** Signed seconds with millisecond digits, e.g. +0.137 s */
        fun formatOffset(nanos: Long): String = "%+.3f s".format(nanos / 1e9)
    }
}
