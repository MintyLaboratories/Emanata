package minty.emanata.ui

import minty.emanata.data.BookEntry
import minty.emanata.data.EntryType
import minty.emanata.data.FavoriteKind
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class FavoriteSectionsTest {

    private fun entry(name: String) = BookEntry(File("/x/$name"), name, true, EntryType.FOLDER, 0, 0)

    private val lib = entry("lib")
    private val set = entry("set")
    private val comic = entry("comic")
    private val unknown = entry("unknown")
    private val kinds = mapOf(lib.path to FavoriteKind.LIBRARY, set.path to FavoriteKind.SET, comic.path to FavoriteKind.COMIC)

    @Test
    fun sectionsComeInFixedOrderKeepingEntryOrder() {
        val other = entry("other comic")
        val groups = FavoriteSections.arrange(listOf(comic, set, other, lib)) {
            if (it === other) FavoriteKind.COMIC else kinds[it.path]
        }
        assertEquals(listOf(FavoriteKind.LIBRARY, FavoriteKind.SET, FavoriteKind.COMIC), groups.map { it.kind })
        assertEquals(listOf(comic, other), groups[2].entries)
    }

    @Test
    fun emptySectionsAreLeftOut() {
        val groups = FavoriteSections.arrange(listOf(comic)) { kinds[it.path] }
        assertEquals(listOf(FavoriteKind.COMIC), groups.map { it.kind })
    }

    @Test
    fun unclassifiedWaitUnderSets() {
        val groups = FavoriteSections.arrange(listOf(set, unknown)) { kinds[it.path] }
        assertEquals(listOf(FavoriteGroup(FavoriteKind.SET, listOf(set, unknown), pending = 1)), groups)
    }

    @Test
    fun collapsedSectionsShowOnlyTheirHeader() {
        val groups = FavoriteSections.arrange(listOf(lib, set, comic)) { kinds[it.path] }
        val rows = FavoriteSections.rows(groups, setOf(FavoriteKind.SET)) { it.key }
        assertEquals(
            listOf(
                SectionRow(FavoriteKind.LIBRARY, "library", 1, collapsed = false, loading = false), lib,
                SectionRow(FavoriteKind.SET, "set", 1, collapsed = true, loading = false),
                SectionRow(FavoriteKind.COMIC, "comic", 1, collapsed = false, loading = false), comic
            ),
            rows
        )
    }
}
