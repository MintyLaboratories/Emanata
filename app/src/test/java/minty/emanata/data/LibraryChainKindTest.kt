package minty.emanata.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import minty.emanata.ai.ChainKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which default chain a book runs: kept by book ID and by folder, moved and forgotten with them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LibraryChainKindTest {

    private lateinit var library: Library

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        library = Library(context)
    }

    private val a = BookRef(BookId(0xa), "/books/series/a.cbz")
    private val b = BookRef(BookId(0xb), "/books/series/b.cbz")

    @Test
    fun aBooksKindIsKeptByItsId() {
        assertNull(library.chainKind(a.id))
        library.setChainKind(a, ChainKind.MANGA)
        assertEquals(ChainKind.MANGA, library.chainKind(a.id))
        assertEquals(true, library.kept()[a.id]?.settings)
        assertNull(library.chainKind(b.id))
        library.setChainKind(a, ChainKind.OTHER)
        assertEquals(ChainKind.OTHER, library.chainKind(a.id))
        library.setChainKind(a, null)
        assertNull("back to its folder's or its direction", library.chainKind(a.id))
    }

    @Test
    fun aFoldersKindIsKeptByItsPathAndFollowsAMove() {
        assertNull(library.folderChainKind("/books/series"))
        library.setFolderChainKind("/books/series", ChainKind.MANGA)
        assertEquals(ChainKind.MANGA, library.folderChainKind("/books/series"))
        assertNull(library.folderChainKind("/books"))
        library.replacePath("/books/series", "/books/renamed")
        assertNull(library.folderChainKind("/books/series"))
        assertEquals(ChainKind.MANGA, library.folderChainKind("/books/renamed"))
        library.removePathsUnder("/books/renamed")
        assertNull(library.folderChainKind("/books/renamed"))
        library.setFolderChainKind("/books/series", ChainKind.OTHER)
        library.setFolderChainKind("/books/series", null)
        assertNull(library.folderChainKind("/books/series"))
    }

    @Test
    fun kindsMoveWithTheBookAndGoWithLibraryData() {
        library.setChainKind(a, ChainKind.MANGA)
        library.carryOver(a.id, b, IntArray(0), 0)
        assertNull(library.chainKind(a.id))
        assertEquals(ChainKind.MANGA, library.chainKind(b.id))
        library.forgetBooks(setOf(b.id))
        assertNull(library.chainKind(b.id))
        library.setChainKind(a, ChainKind.OTHER)
        library.setFolderChainKind("/books/series", ChainKind.MANGA)
        assertTrue(library.mayHaveData(a.id))
        library.clearLibraryData()
        assertNull(library.chainKind(a.id))
        assertNull(library.folderChainKind("/books/series"))
    }

    @Test
    fun aPackedFoldersKindBecomesTheBooks() {
        val folder = "/books/loose"
        val images = listOf(BookRef(BookId(0x1), "$folder/p1.png"), BookRef(BookId(0x2), "$folder/p2.png"))
        val packed = BookRef(BookId(0xc), "/books/loose.cbz")
        library.setChainKind(packed, ChainKind.OTHER)
        library.setFolderChainKind(folder, ChainKind.MANGA)
        library.carryFolder(folder, images, packed, images.size)
        assertEquals(ChainKind.MANGA, library.chainKind(packed.id))
        library.setFolderChainKind(folder, null)
        library.carryFolder(folder, images, packed, images.size)
        assertNull("a folder without one leaves the book following its direction", library.chainKind(packed.id))
    }

    @Test
    fun kindsSurviveABackup() {
        library.setChainKind(a, ChainKind.MANGA)
        library.setFolderChainKind("/books/series", ChainKind.OTHER)
        val backup = library.exportAll()
        library.clearLibraryData()
        library.importAll(backup)
        assertEquals(ChainKind.MANGA, library.chainKind(a.id))
        assertEquals(ChainKind.OTHER, library.folderChainKind("/books/series"))
    }
}
