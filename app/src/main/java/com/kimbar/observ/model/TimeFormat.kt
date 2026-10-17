package com.kimbar.observ.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** On-screen test time formats (DESIGN.md 4). Files always use [A]. */
enum class TimeFormat { A, B }

object Fmt {
    private const val TENTH = 100_000_000L

    /** Rounds to tenths of a second, half up. */
    fun tenths(ns: Long): Long = Math.floorDiv(ns + TENTH / 2, TENTH)

    /** `MMM:SS.s` / `H:MM:SS.s`, rounded to 0.1 s, `-` for negative. */
    fun testTime(ns: Long, format: TimeFormat = TimeFormat.A): String {
        val t = tenths(ns)
        val sign = if (t < 0) "-" else ""
        val a = abs(t)
        val tenth = a % 10
        val totalS = a / 10
        val s = totalS % 60
        return when (format) {
            TimeFormat.A -> "%s%03d:%02d.%d".format(sign, totalS / 60, s, tenth)
            TimeFormat.B -> "%s%d:%02d:%02d.%d".format(sign, totalS / 3600, (totalS / 60) % 60, s, tenth)
        }
    }

    /** A reading in whole seconds: `MMM:SS` / `H:MM:SS`. */
    fun reading(seconds: Int, format: TimeFormat = TimeFormat.A): String = when (format) {
        TimeFormat.A -> "%03d:%02d".format(seconds / 60, seconds % 60)
        TimeFormat.B -> "%d:%02d:%02d".format(seconds / 3600, (seconds / 60) % 60, seconds % 60)
    }

    /** Signed seconds with one decimal, e.g. `+0.3 s`. */
    fun delta(ns: Long): String {
        val t = tenths(ns)
        val sign = if (t < 0) "-" else "+"
        return "%s%d.%d s".format(sign, abs(t) / 10, abs(t) % 10)
    }

    /** Seconds with millisecond digits, for durations: `83.412`. */
    fun seconds3(ns: Long): String {
        val ms = Math.floorDiv(ns + 500_000L, 1_000_000L)
        val sign = if (ms < 0) "-" else ""
        return "%s%d.%03d".format(sign, abs(ms) / 1000, abs(ms) % 1000)
    }

    /** Unix epoch seconds with microsecond digits: `1792238602.123456`. */
    fun epoch(ns: Long): String =
        "%d.%06d".format(Math.floorDiv(ns, 1_000_000_000L), Math.floorMod(ns, 1_000_000_000L) / 1000)

    fun instant(ns: Long): Instant =
        Instant.ofEpochSecond(Math.floorDiv(ns, 1_000_000_000L), Math.floorMod(ns, 1_000_000_000L))

    private val localFull = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS")
    private val localClock = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val localDate = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun localFull(ns: Long, zone: ZoneId = ZoneId.systemDefault()): String = localFull.format(instant(ns).atZone(zone))
    fun localClock(ns: Long, zone: ZoneId = ZoneId.systemDefault()): String = localClock.format(instant(ns).atZone(zone))
    fun localDate(ns: Long, zone: ZoneId = ZoneId.systemDefault()): String = localDate.format(instant(ns).atZone(zone))
}

/** Splitting a reading into the digit wheels of a format, and back (DESIGN.md 4). */
object Wheels {
    /** Highest value of each wheel, left to right. */
    fun maxima(format: TimeFormat): List<Int> = when (format) {
        TimeFormat.A -> listOf(9, 9, 9, 5, 9) // MMM:SS
        TimeFormat.B -> listOf(9, 5, 9, 5, 9) // H:MM:SS
    }

    /** Index after which a ':' is drawn. */
    fun separatorsAfter(format: TimeFormat): Set<Int> = when (format) {
        TimeFormat.A -> setOf(2)
        TimeFormat.B -> setOf(0, 2)
    }

    fun digits(seconds: Int, format: TimeFormat): List<Int> {
        val s = seconds % 60
        val m = seconds / 60
        return when (format) {
            TimeFormat.A -> listOf(m / 100 % 10, m / 10 % 10, m % 10, s / 10, s % 10)
            TimeFormat.B -> listOf(m / 60 % 10, m % 60 / 10, m % 10, s / 10, s % 10)
        }
    }

    fun seconds(digits: List<Int>, format: TimeFormat): Int {
        val s = digits[3] * 10 + digits[4]
        val m = when (format) {
            TimeFormat.A -> digits[0] * 100 + digits[1] * 10 + digits[2]
            TimeFormat.B -> digits[0] * 60 + digits[1] * 10 + digits[2]
        }
        return m * 60 + s
    }
}
