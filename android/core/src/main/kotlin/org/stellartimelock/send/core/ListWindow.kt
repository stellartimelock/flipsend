package org.stellartimelock.send.core

/**
 * Caps how many rows a ScrollView list may inflate at once.
 * FlipSend builds rows in a LinearLayout, so a long received
 * history would measure every child on every scroll.
 */
data class ListPage<T>(
    val shown: List<T>,
    val more: Int
)

object ListWindow {
    const val PAGE = 20
    const val HOME_PAGE = 8
    const val SHOW_MORE = "Show more"
    const val SHOW_LESS = "Show less"

    fun <T> take(items: List<T>, shown: Int): ListPage<T> {
        val limit = shown.coerceAtLeast(0)
        if (items.size <= limit) return ListPage(items, 0)
        return ListPage(items.take(limit), items.size - limit)
    }

    fun nextLimit(current: Int, page: Int = PAGE, preview: Int = 0): Int {
        val start = current.coerceAtLeast(0)
        return if (start <= preview) page else start + page
    }
}
