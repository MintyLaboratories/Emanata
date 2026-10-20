package com.infinity.comicreader.data

import com.infinity.comicreader.data.StartupLocation.CUSTOM
import com.infinity.comicreader.data.StartupLocation.FIRST_FAVORITE
import com.infinity.comicreader.data.StartupLocation.LAST
import com.infinity.comicreader.data.StartupLocation.RECENT_FAVORITE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StartupLocationTest {

    private val dirs = setOf("/r/last", "/r/a", "/r/b", "/r/b/inner", "/r/custom")

    private fun resolve(
        choice: String,
        custom: String? = "/r/custom",
        last: String? = "/r/last",
        shown: List<String> = listOf("/r/book.cbz", "/r/b", "/r/a"),
        added: List<String> = listOf("/r/a", "/r/book.cbz", "/r/b", "/r/b/inner"),
        history: List<String> = emptyList()
    ) = StartupLocation.resolve(choice, custom, last, { shown }, added, history) { it in dirs }

    @Test
    fun lastSeen() {
        assertEquals("/r/last", resolve(LAST))
    }

    @Test
    fun firstFavoriteFolderSkipsFiles() {
        assertEquals("/r/b", resolve(FIRST_FAVORITE))
    }

    @Test
    fun recentFavoriteHoldsTheLastBookRead() {
        assertEquals("/r/b/inner", resolve(RECENT_FAVORITE, history = listOf("/r/b/inner/1.cbz", "/r/a/2.cbz")))
        assertEquals("/r/b", resolve(RECENT_FAVORITE, history = listOf("/r/b/x/1.cbz")))
    }

    @Test
    fun recentFavoriteFallsBackToTheNewestFolder() {
        assertEquals("/r/a", resolve(RECENT_FAVORITE, history = listOf("/elsewhere/1.cbz")))
        // A sibling whose name only starts the same is not inside.
        assertEquals("/r/a", resolve(RECENT_FAVORITE, history = listOf("/r/bx/1.cbz")))
    }

    @Test
    fun customFolder() {
        assertEquals("/r/custom", resolve(CUSTOM))
    }

    @Test
    fun missingChoiceFallsBackToLastSeen() {
        assertEquals("/r/last", resolve(CUSTOM, custom = "/gone"))
        assertEquals("/r/last", resolve(FIRST_FAVORITE, shown = emptyList()))
        assertEquals("/r/last", resolve(RECENT_FAVORITE, added = emptyList()))
        assertNull(resolve(LAST, last = "/gone"))
    }
}
