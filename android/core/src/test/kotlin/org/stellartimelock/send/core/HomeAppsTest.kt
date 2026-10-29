package org.stellartimelock.send.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.TimeZone

class HomeAppsTest {
    @Test
    fun newestAppShowsFirstAndOlderOnesCollapse() {
        val zone = TimeZone.getTimeZone("UTC")
        val older = apk("old", "Notes", "1.0", 1).copy(
            packageName = "app.notes",
            hasInstalled = true,
            installedCode = 2,
            installedVersion = "2.0"
        )
        val newer = apk("new", "Notes", "2.0", 2).copy(
            packageName = "app.notes",
            hasInstalled = true,
            installedCode = 1,
            installedVersion = "1.0"
        )
        val other = apk("mail", "Mail", "3.1", 9)
        val times = mapOf("old" to 1_000L, "new" to 5_000L, "mail" to 3_000L)
        val collapsed = HomeApps.list(listOf(older, other, newer), times, expanded = false, zone = zone)
        assertEquals(listOf("new"), collapsed.shown.map { it.id })
        assertEquals("Notes", collapsed.shown[0].name)
        assertEquals("2.0 (2)", collapsed.shown[0].version)
        assertEquals(UsDates.format(5_000, zone), collapsed.shown[0].received)
        assertEquals(HomeApps.UPDATE, collapsed.shown[0].action)
        assertEquals(2, collapsed.more)
        val open = HomeApps.list(listOf(older, other, newer), times, expanded = true, zone = zone)
        assertEquals(listOf("new", "mail", "old"), open.shown.map { it.id })
        assertEquals(0, open.more)
        assertEquals(HomeApps.INSTALL, open.shown[1].action)
        assertEquals("", open.shown[2].action)
        assertTrue(HomeApps.isButton(HomeApps.INSTALL))
        assertFalse(HomeApps.isButton(HomeApps.INSTALLED))
    }

    @Test
    fun expandedHomeAppsStayOnAPageSoTheHomeScrollViewDoesNotInflateEveryRow() {
        val files = (1..20).map { index ->
            apk("app$index", "App $index", "1.$index", index.toLong())
        }
        val times = files.associate { it.id to it.id.removePrefix("app").toLong() }
        val collapsed = HomeApps.list(files, times, expanded = false)
        assertEquals(HomeApps.PREVIEW, collapsed.shown.size)
        assertEquals(19, collapsed.more)
        val page = HomeApps.list(files, times, expanded = true)
        assertEquals(HomeApps.PAGE, page.shown.size)
        assertEquals(12, page.more)
        val more = HomeApps.list(files, times, expanded = true, limit = HomeApps.PAGE * 2)
        assertEquals(16, more.shown.size)
        assertEquals(4, more.more)
        val open = HomeApps.list(files, times, expanded = true, limit = 20)
        assertEquals(20, open.shown.size)
        assertEquals(0, open.more)
    }

    @Test
    fun aFileThatJustArrivedSortsAheadOfOlderSaves() {
        val live = apk("live", "Live", "9", 9)
        val saved = apk("saved", "Saved", "1", 1)
        val page = HomeApps.list(listOf(saved, live), mapOf("saved" to 10L), expanded = false)
        assertEquals("live", page.shown.single().id)
        assertEquals(HomeApps.JUST_NOW, page.shown.single().received)
        assertEquals(1, page.more)
    }

    private fun apk(id: String, name: String, version: String, code: Long) = ReceiveFile(
        id = id,
        name = "$name.apk",
        kind = "apk",
        path = "/$name.apk",
        appName = name,
        packageName = "app.$id",
        incomingVersion = version,
        incomingCode = code
    )
}