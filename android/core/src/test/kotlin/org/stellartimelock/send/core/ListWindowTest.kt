package org.stellartimelock.send.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ListWindowTest {
    @Test
    fun aLongListOnlyHandsOverTheFirstPage() {
        val items = (1..80).toList()
        val first = ListWindow.take(items, ListWindow.PAGE)
        assertEquals(ListWindow.PAGE, first.shown.size)
        assertEquals((1..20).toList(), first.shown)
        assertEquals(60, first.more)
        val next = ListWindow.take(items, ListWindow.nextLimit(first.shown.size))
        assertEquals(40, next.shown.size)
        assertEquals(40, next.more)
        val all = ListWindow.take(items, 80)
        assertEquals(80, all.shown.size)
        assertEquals(0, all.more)
    }

    @Test
    fun anEmptyOrShortListHasNoMoreButton() {
        assertEquals(0, ListWindow.take(emptyList<String>(), ListWindow.PAGE).more)
        val short = ListWindow.take(listOf("a", "b"), ListWindow.PAGE)
        assertEquals(listOf("a", "b"), short.shown)
        assertEquals(0, short.more)
        assertEquals(ListWindow.PAGE, ListWindow.nextLimit(0))
        assertEquals(ListWindow.HOME_PAGE, ListWindow.nextLimit(HomeApps.PREVIEW, HomeApps.PAGE, HomeApps.PREVIEW))
        assertEquals(16, ListWindow.nextLimit(8, HomeApps.PAGE, HomeApps.PREVIEW))
    }
}
