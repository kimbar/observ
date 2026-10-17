package com.kimbar.observ.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class JsonTest {
    @Test
    fun roundTripKeepsEverything() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
        var t = TestData("rt", 1L)
        t = TestOps.addItem(t, Item("a", ItemType.VIDEO, Moment(5, 3, 10), Moment(9, 3, 14), file = "v.mp4"))
        t = TestOps.addItem(t, Item("b", ItemType.NOTE, Moment(6, 4, 99), text = "x\ny", active = false))
        t = TestOps.addSync(t, Sync("s", "a", 61, frame = 2, frameOffsetNs = 66))
        t = TestOps.markExported(t)
        val text = json.encodeToString(TestData.serializer(), t)
        assertEquals(t, json.decodeFromString(TestData.serializer(), text))
        assertEquals(t, json.decodeFromString(TestData.serializer(), text.replace("\"name\"", "\"future\": 1, \"name\"")))
    }
}
