package org.stellartimelock.send.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class FileNamesTest {

    @Test
    fun sanitizesControlChars() {
        assertEquals("file", FileNames.sanitize(null))
        assertEquals("hello_world.txt", FileNames.sanitize("hello\u0001world.txt"))
    }

    @Test
    fun avoidsWindowsReserved() {
        assertEquals("_CON.txt", FileNames.sanitize("CON.txt"))
        assertEquals("_NUL", FileNames.sanitize("NUL"))
    }

    @Test
    fun allocateUnique() {
        val existing = setOf("file.txt", "file (1).txt")
        assertEquals("file (2).txt", FileNames.allocate("file.txt", existing))
    }

    @Test
    fun folderLayoutRouting() {
        assertEquals("images", FolderLayout.subfolder("photo.jpg"))
        assertEquals("documents", FolderLayout.subfolder("report.pdf"))
        assertEquals("media", FolderLayout.subfolder("video.mp4"))
        assertEquals("apps", FolderLayout.subfolder("app.apk"))
        assertEquals("other", FolderLayout.subfolder("data.bin"))
    }

    @Test
    fun sha256HexLength() {
        val h = sha256Hex("test".encodeToByteArray())
        assertEquals(64, h.length)
        assertEquals(true, h.all { it in '0'..'9' || it in 'a'..'f' })
    }
}
