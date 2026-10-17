package com.kimbar.observ.data

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.kimbar.observ.model.Moment

/** Captures [Moment]s: monotonic counter, boot number and the phone's wall clock. */
class PhoneClock(context: Context) {

    /** Changes on every reboot; the counter restarts with it. */
    val boot: Int = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)

    fun counter(): Long = SystemClock.elapsedRealtimeNanos()

    fun now(): Moment = at(counter())

    /** A moment for a counter value captured earlier (e.g. a camera or audio timestamp). */
    fun at(counterNs: Long): Moment {
        val nowCounter = SystemClock.elapsedRealtimeNanos()
        val nowEpoch = System.currentTimeMillis() * 1_000_000L
        return Moment(counterNs, boot, nowEpoch - (nowCounter - counterNs))
    }
}
