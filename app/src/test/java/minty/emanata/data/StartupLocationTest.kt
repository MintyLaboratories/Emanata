package minty.emanata.data

import minty.emanata.data.StartupLocation.CUSTOM
import minty.emanata.data.StartupLocation.FIRST_FAVORITE
import minty.emanata.data.StartupLocation.LAST
import minty.emanata.data.StartupLocation.RECENT_FAVORITE
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
    ) = StartupLocation.resolve(choice, custom, last, { shown }, { added }, { history }) { it in dirs }

    @Test
    fun lastSeen() {
        assertEquals("/r/last", resolve(LAST))
    }

    @Test
    fun lastSeenReadsNoLists() {
        val unread: () -> List<String> = { throw AssertionError("read") }
        assertEquals("/r/last", StartupLocation.resolve(LAST, null, "/r/last", unread, unread, unread) { it in dirs })
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

    @Test
    fun aMissingCustomOrLastFolderWithAParentLeftIsKeptForBrowseToClimbOutOf() {
        val reachable = { p: String -> p.startsWith("/r/") }
        fun resolveReachable(choice: String, custom: String?, last: String?) =
            StartupLocation.resolve(choice, custom, last, { emptyList() }, { emptyList() }, { emptyList() }, reachable) { it in dirs }
        assertEquals("/r/custom/gone", resolveReachable(CUSTOM, "/r/custom/gone", "/r/last"))
        assertEquals("/r/last/gone", resolveReachable(LAST, null, "/r/last/gone"))
        // Nothing of it left: the last folder seen, else nothing.
        assertEquals("/r/last", resolveReachable(CUSTOM, "/unplugged/x", "/r/last"))
        assertNull(resolveReachable(LAST, null, "/unplugged/x"))
    }
}
