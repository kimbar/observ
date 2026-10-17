package com.kimbar.observ.time

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import kotlin.math.abs

/** Parses a time the user read off an external clock. */
object TimeInput {

    private val timeFmt: DateTimeFormatter = DateTimeFormatterBuilder()
        .appendPattern("H:mm")
        .optionalStart().appendPattern(":ss").optionalEnd()
        .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true).optionalEnd()
        .toFormatter()

    /**
     * Accepts "HH:mm", "HH:mm:ss", "HH:mm:ss.f…" (any number of fraction digits, '.' or ','),
     * optionally preceded by "yyyy-MM-dd ". Without a date, the day closest to [nearEpochNanos]
     * is used, so a sync just after midnight works. Returns epoch nanos or null.
     */
    fun parse(text: String, nearEpochNanos: Long, zone: ZoneId = TimeKeeper.zone): Long? {
        val t = text.trim().replace(',', '.')
        if (t.isEmpty()) return null
        return try {
            val parts = t.split(Regex("[ T]+"))
            if (parts.size == 2) {
                val date = LocalDate.parse(parts[0])
                val time = LocalTime.parse(parts[1], timeFmt)
                toNanos(LocalDateTime.of(date, time), zone)
            } else {
                val time = LocalTime.parse(t, timeFmt)
                val nearDate = TimeKeeper.instant(nearEpochNanos).atZone(zone).toLocalDate()
                listOf(nearDate.minusDays(1), nearDate, nearDate.plusDays(1))
                    .map { toNanos(LocalDateTime.of(it, time), zone) }
                    .minBy { abs(it - nearEpochNanos) }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun toNanos(ldt: LocalDateTime, zone: ZoneId): Long {
        val i = ldt.atZone(zone).toInstant()
        return i.epochSecond * 1_000_000_000L + i.nano
    }

    /** Default text for the input box: the phone's estimate rounded to the nearest second. */
    fun suggestion(epochNanos: Long, zone: ZoneId = TimeKeeper.zone): String {
        val rounded = Math.floorDiv(epochNanos + 500_000_000L, 1_000_000_000L) * 1_000_000_000L
        return DateTimeFormatter.ofPattern("HH:mm:ss.000")
            .format(TimeKeeper.instant(rounded).atZone(zone))
    }
}
