package com.kimbar.observ.model

import kotlinx.serialization.Serializable

/**
 * A point in time as captured on the phone.
 *
 * [counterNs] is the monotonic counter (elapsedRealtimeNanos) of boot [boot]; [epochNs] is the
 * phone's own wall clock at that moment. See DESIGN.md section 3.
 */
@Serializable
data class Moment(val counterNs: Long, val boot: Int, val epochNs: Long) {
    fun plus(ns: Long) = Moment(counterNs + ns, boot, epochNs + ns)
}

enum class ItemType { MARK, NOTE, PHOTO, VIDEO, AUDIO }

@Serializable
data class Item(
    val id: String,
    val type: ItemType,
    val moment: Moment,
    /** End of a VIDEO or AUDIO recording. */
    val end: Moment? = null,
    val file: String = "",
    val text: String = "",
    val active: Boolean = true,
) {
    val canSync: Boolean get() = type == ItemType.MARK || type == ItemType.PHOTO || type == ItemType.VIDEO
}

@Serializable
data class Sync(
    val id: String,
    val itemId: String,
    /** The external clock reading in whole seconds. */
    val readingS: Int,
    /** VIDEO only: frame number (0-based) and its offset from the video start. */
    val frame: Int? = null,
    val frameOffsetNs: Long = 0,
    val active: Boolean = true,
)

@Serializable
data class TestData(
    val name: String,
    val createdEpochNs: Long,
    val active: Boolean = true,
    val items: List<Item> = emptyList(),
    val syncs: List<Sync> = emptyList(),
    /** Per boot: wall-clock epoch of counter zero, measured when the boot was first seen. */
    val bootBases: Map<Int, Long> = emptyMap(),
    val firstBoot: Int? = null,
    val exportCount: Int = 0,
    val changedSinceExport: Boolean = true,
)
