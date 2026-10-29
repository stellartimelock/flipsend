package org.stellartimelock.send.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PhoneInviteTest {
    @Test
    fun aCodeWorksOnceAndExpiresInADay() {
        val token = PhoneInvite.mint(nowMs = 1_000)
        assertTrue(PhoneInvite.looksLike(token.code))
        assertTrue(PhoneInvite.live(token, 1_000))
        assertTrue(PhoneInvite.live(token, 1_000 + PhoneInvite.TTL_MS))
        assertFalse(PhoneInvite.live(token, 1_000 + PhoneInvite.TTL_MS + 1))
        assertFalse(PhoneInvite.live(PhoneInvite.burn(token), 2_000))
        assertEquals(RelayRoom.invite(token.code), PhoneInvite.room(token.code.lowercase()))
        assertTrue(PhoneInvite.emailBody(token.code, PhoneInvite.redeemLink(token.code)).contains("one-time code", ignoreCase = true))
        assertTrue(PhoneInvite.redeemLink(token.code).startsWith("https://flipsend.app/invite?code="))
    }
}
