package org.stellartimelock.send.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SaveRulesTest {

    @Test
    fun allowsFirstSave() {
        val decision = SaveRules.decide(null, "{}".encodeToByteArray())
        assertEquals(OverwriteKind.ALLOW, decision.kind)
    }

    @Test
    fun blocksEmptySave() {
        val existing = "{\"items\":[1,2,3]}".encodeToByteArray()
        val incoming = "{}".encodeToByteArray()
        val decision = SaveRules.decide(existing, incoming)
        assertEquals(OverwriteKind.NEEDS_CONFIRM, decision.kind)
    }

    @Test
    fun blocksHugeShrink() {
        val existing = buildString { repeat(1000) { append("{\"k\":\"v\"},") } }.encodeToByteArray()
        val incoming = "{}".encodeToByteArray()
        val decision = SaveRules.decide(existing, incoming)
        assertEquals(OverwriteKind.NEEDS_CONFIRM, decision.kind)
    }

    @Test
    fun allowsSimilarSize() {
        val existing = "{\"items\":[1,2,3,4,5]}".encodeToByteArray()
        val incoming = "{\"items\":[1,2,3,4]}".encodeToByteArray()
        val decision = SaveRules.decide(existing, incoming)
        assertEquals(OverwriteKind.ALLOW, decision.kind)
    }

    @Test
    fun wouldReplaceWithMuchSmaller() {
        assertTrue(SaveRules.wouldReplaceWithMuchSmaller(1000L, 10L))
        assertTrue(SaveRules.wouldReplaceWithMuchSmaller(1000L, 499L))
        assertTrue(!SaveRules.wouldReplaceWithMuchSmaller(1000L, 500L))
        assertTrue(!SaveRules.wouldReplaceWithMuchSmaller(0L, 10L))
    }
}
