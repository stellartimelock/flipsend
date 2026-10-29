package org.stellartimelock.send.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReceivedLogTest {

    @Test
    fun roundTrip() {
        val entries = listOf(
            ReceivedEntry("id1", "photo.jpg", 1_024_000L, 1_700_000_000_000L, "file"),
            ReceivedEntry("id2", "note.txt", 128L, 1_700_000_001_000L, "text")
        )
        val json = ReceivedLog.encode(entries)
        val decoded = ReceivedLog.decode(json)
        assertEquals(2, decoded.size)
        assertEquals("id1", decoded[0].id)
        assertEquals("photo.jpg", decoded[0].name)
        assertEquals(1_024_000L, decoded[0].bytes)
        assertEquals("file", decoded[0].kind)
        assertEquals("id2", decoded[1].id)
        assertEquals("note.txt", decoded[1].name)
    }

    @Test
    fun emptyDecodeReturnsEmpty() {
        assertEquals(emptyList(), ReceivedLog.decode(""))
        assertEquals(emptyList(), ReceivedLog.decode("{}"))
    }

    @Test
    fun decodeMalformedReturnsEmpty() {
        assertEquals(emptyList(), ReceivedLog.decode("not json"))
    }
}
