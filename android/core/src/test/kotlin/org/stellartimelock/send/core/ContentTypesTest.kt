package org.stellartimelock.send.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContentTypesTest {
    @Test
    fun videosAreNotHiddenAsGenericBytes() {
        assertEquals("video/mp4", ContentTypes.of("clip.mp4"))
        assertEquals("video/mp4", ContentTypes.of("inbox/friend-clip.mp4"))
        assertEquals("video/quicktime", ContentTypes.of("clip.MOV"))
        assertEquals("video/webm", ContentTypes.of("clip.webm"))
        assertEquals("video/mp4", ContentTypes.of("clip.mp4", "application/octet-stream"))
        assertEquals("video/mp4", ContentTypes.of("clip.mp4", "*/*"))
        assertFalse(ContentTypes.generic(ContentTypes.of("friend-video.mp4")))
    }

    @Test
    fun aDeclaredTypeWinsUnlessItIsGeneric() {
        assertEquals("video/mp4", ContentTypes.of("file.bin", "video/mp4"))
        assertEquals("image/jpeg", ContentTypes.of("photo.jpg", "application/octet-stream"))
        assertEquals("application/pdf", ContentTypes.of("notes.pdf"))
    }

    @Test
    fun theFilePickerAsksForPhotosVideosAndAnything() {
        assertArrayEquals(arrayOf("image/*", "video/*", "*/*"), ContentTypes.PICK)
        assertTrue(ContentTypes.PICK.contains("video/*"))
    }
}
