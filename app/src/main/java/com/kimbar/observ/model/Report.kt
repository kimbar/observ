package com.kimbar.observ.model

import java.time.ZoneId

/** Generates `events.txt` and `syncs.txt` from a Test (DESIGN.md 7). */
object Report {

    fun header(t: TestData, device: String, zone: ZoneId): String {
        val tt = TestTime(t)
        val lines = mutableListOf(
            "observ test: ${t.name}",
            "device: $device",
            "created: ${Fmt.localFull(t.createdEpochNs, zone)} (${zone.id})",
            "status: ${if (t.active) "active" else "inactive"}",
        )
        val avg = tt.averageOffsetNs
        if (avg == null) {
            lines += "sync: not synced (test_time columns are empty)"
        } else {
            lines += "sync: ${tt.activeSyncs.size} active of ${t.syncs.size}, " +
                "spread ${Fmt.delta(tt.spreadNs ?: 0)}, test time 000:00.0 at about ${Fmt.localFull(avg, zone)}"
        }
        if (t.bootBases.size > 1) {
            lines += "warning: the phone rebooted during this test; times after the reboot are bridged via the phone clock"
        }
        lines += "test times: MMM:SS.s rounded to 0.1 s; local_time/unix_epoch: phone clock"
        return lines.joinToString("") { "# $it\n" }
    }

    fun events(t: TestData, device: String, zone: ZoneId = ZoneId.systemDefault()): String {
        val tt = TestTime(t)
        val sb = StringBuilder(header(t, device, zone))
        sb.append(
            listOf(
                "test_time", "local_time", "unix_epoch", "type", "active", "end_test_time", "duration_s",
                "file", "text", "phone_counter_ns", "after_reboot",
            ).joinToString("\t")
        ).append('\n')
        for (i in t.items.sortedBy { tt.timeline(it.moment) }) {
            val m = i.moment
            sb.append(
                listOf(
                    tt.testTimeNs(m)?.let { Fmt.testTime(it) } ?: "",
                    Fmt.localFull(m.epochNs, zone),
                    Fmt.epoch(m.epochNs),
                    i.type.name,
                    if (i.active) "1" else "0",
                    i.end?.let { e -> tt.testTimeNs(e)?.let { Fmt.testTime(it) } } ?: "",
                    i.end?.let { Fmt.seconds3(tt.timeline(it) - tt.timeline(m)) } ?: "",
                    i.file,
                    escape(i.text),
                    m.counterNs.toString(),
                    if (tt.afterReboot(m)) "1" else "0",
                ).joinToString("\t")
            ).append('\n')
        }
        return sb.toString()
    }

    fun syncs(t: TestData, device: String, zone: ZoneId = ZoneId.systemDefault()): String {
        val tt = TestTime(t)
        val sb = StringBuilder(header(t, device, zone))
        sb.append(
            listOf(
                "test_time", "reading", "adjustment_s", "delta_s", "method", "source_file", "frame", "active",
                "local_time", "unix_epoch", "phone_counter_ns",
            ).joinToString("\t")
        ).append('\n')
        val rows = t.syncs.mapNotNull { s -> tt.moment(s)?.let { s to it } }.sortedBy { tt.timeline(it.second) }
        for ((s, m) in rows) {
            val item = tt.item(s.itemId)!!
            sb.append(
                listOf(
                    tt.testTimeNs(m)?.let { Fmt.testTime(it) } ?: "",
                    Fmt.reading(s.readingS),
                    Fmt.seconds3(tt.adjustmentNs(s)).let { if (it.startsWith("-")) it else "+$it" },
                    tt.deltaNs(s)?.let { Fmt.delta(it).removeSuffix(" s") } ?: "",
                    item.type.name,
                    item.file,
                    s.frame?.let { (it + 1).toString() } ?: "",
                    if (tt.counts(s)) "1" else "0",
                    Fmt.localFull(m.epochNs, zone),
                    Fmt.epoch(m.epochNs),
                    m.counterNs.toString(),
                ).joinToString("\t")
            ).append('\n')
        }
        return sb.toString()
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "").replace("\n", "\\n")
}
