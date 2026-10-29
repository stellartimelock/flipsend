package org.stellartimelock.send.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CloudUpdateTest {
    @Test
    fun aNewerSignedBuildIsOfferedOnce() {
        val manifest = CloudUpdate.parse(
            """{"versionName":"1.5.45","versionCode":56,"apkUrl":"https://flipsend.app/FlipSend-1.5.45.apk","sha256":"","signerPrefix":"7994727d"}"""
        )!!
        assertEquals(56, manifest.versionCode)
        assertEquals("FlipSend-1.5.45.apk", manifest.apkUrl.substringAfterLast('/'))
        assertTrue(CloudUpdate.newerThan(manifest, 55))
        assertFalse(CloudUpdate.newerThan(manifest, 56))
        assertTrue(CloudUpdate.signerMatches("7994727d89e8", manifest))
        assertFalse(CloudUpdate.signerMatches("aaaaaaaa", manifest))
        assertTrue(CloudUpdate.shouldCheck(alreadyChecked = false, pushAsked = false))
        assertFalse(CloudUpdate.shouldCheck(alreadyChecked = true, pushAsked = false))
        assertTrue(CloudUpdate.shouldCheck(alreadyChecked = true, pushAsked = true))
    }

    @Test
    fun httpAndEmptyManifestsAreIgnored() {
        assertNull(CloudUpdate.parse("""{"versionCode":56,"apkUrl":"http://evil.example/FlipSend.apk"}"""))
        assertNull(CloudUpdate.parse("""{"versionCode":0,"apkUrl":"https://flipsend.app/FlipSend.apk"}"""))
        assertNull(CloudUpdate.parse("not-json"))
    }
}
