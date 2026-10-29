package org.stellartimelock.send.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TransferCapTest {
    @Test
    fun fiftyGigabytesIsTheSharedPoolNotThePersonalCap() {
        assertEquals(TransferCap.FREE_MONTH_BYTES, TransferCap.personalLimit(50_000_000_000L))
        assertEquals(TransferCap.FREE_MONTH_BYTES, TransferCap.personalLimit(0))
        assertEquals(TransferCap.ELITE_MONTH_BYTES, TransferCap.personalLimit(TransferCap.ELITE_MONTH_BYTES))
        assertTrue(TransferCap.hit(TransferCap.Kind.FILE_MEMORY).contains("48 MB"))
        assertTrue(TransferCap.hit(TransferCap.Kind.MONTH).contains("100 MB"))
        assertFalse(TransferCap.hit(TransferCap.Kind.MONTH).contains("50 GB"))
        assertEquals(TransferCap.Kind.FILE_MEMORY, TransferCap.classify("This browser cannot keep a file that large in memory."))
        assertEquals(TransferCap.Kind.RATE, TransferCap.classify("Hit 5 MB per minute on the free relay."))
    }
}
