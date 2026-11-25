package minty.emanata.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import minty.emanata.ai.ProcessedStore
import minty.emanata.net.Listings
import minty.emanata.net.RemoteCache
import minty.emanata.reader.NestedArchives
import minty.emanata.reader.PageSeams
import minty.emanata.reader.PageSizes
import minty.emanata.reader.PreviewStore
import minty.emanata.testing.bookId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** What the app can make again is cleared whole, or for one book by its ID; library data never. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BookCachesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var cache: File

    private val a = BookId(0xaa)
    private val b = BookId(0xbb)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        cache = context.cacheDir
        cache.listFiles()?.forEach { it.deleteRecursively() }
        BookFactsCache.reset()
    }

    /** A cache file of [id] in [dir], named as the stores name theirs. */
    private fun fileOf(dir: File, id: BookId, name: String = "0") = File(dir, "${id.hex}-$name.bin").apply {
        parentFile!!.mkdirs()
        writeBytes(ByteArray(100))
    }

    private val perBook get() = listOf(
        PageSizes.dirIn(cache), PageSeams.dirIn(cache), PreviewStore.dirIn(cache), ProcessedStore.dirIn(cache), NestedArchives.dirIn(cache)
    )

    @Test
    fun forgettingABookDropsOnlyItsCachedFiles() {
        val facts = BookFactsCache(context)
        facts.put(a, BookFacts(10, 0))
        facts.put(b, BookFacts(12, 0))
        for (dir in perBook) {
            fileOf(dir, a)
            fileOf(dir, a, "1")
            fileOf(dir, b)
        }
        BookCaches.forget(context, a)
        for (dir in perBook) assertEquals(listOf("${b.hex}-0.bin"), dir.list()!!.toList())
        assertNull(facts.get(a))
        assertNotNull(facts.get(b))
    }

    @Test
    fun aDeletedBookLosesItsCacheUnlessACopyElsewhereKeepsIt() {
        val library = Library(context)
        val shelf = tmp.newFolder("Shelf")
        val one = File(shelf, "one.cbz").apply { writeBytes(ByteArray(300) { it.toByte() }) }
        val two = File(shelf, "two.cbz").apply { writeBytes(ByteArray(400) { it.toByte() }) }
        val copy = File(tmp.newFolder("Other"), "two.cbz").apply { writeBytes(two.readBytes()) }
        val idOne = bookId(one)
        val idTwo = bookId(two)
        bookId(copy)
        library.setRead(BookRef(idOne, one.path), true)
        fileOf(PageSizes.dirIn(cache), idOne)
        fileOf(PageSizes.dirIn(cache), idTwo)
        shelf.deleteRecursively()
        BookCaches.deleted(context, library, shelf.path)
        assertEquals(listOf("${idTwo.hex}-0.bin"), PageSizes.dirIn(cache).list()!!.toList())
        // What the library keeps by ID stays for copies elsewhere.
        assertTrue(library.isRead(idOne))
    }

    @Test
    fun carriedPagesTakeTheirSizesAlongAndTheFactsOnlyWhenEveryPageIsTheSame() {
        val names = listOf("1.jpg", "2.jpg")
        val sizes = PageSizes(PageSizes.dirIn(cache))
        sizes.write("id:${a.hex}", names, arrayOf(intArrayOf(1, 2), intArrayOf(3, 4)))
        BookFactsCache(context).put(a, BookFacts(2, 2014))
        // One page made anew: the facts are left to be read again.
        BookCaches.carry(context, a, b, intArrayOf(0, 1), BookCaches.SamePages(intArrayOf(1), names, listOf("1.jxl", "2.jpg")), move = false)
        val read = sizes.read("id:${b.hex}", listOf("1.jxl", "2.jpg"))!!
        assertNull(read[0])
        assertEquals(listOf(3, 4), read[1]!!.toList())
        assertNull(BookFactsCache(context).get(b))
        // Every page the same, but in another order: the facts are left too.
        val c = BookId(0xcc)
        BookCaches.carry(context, a, c, intArrayOf(1, 0), BookCaches.SamePages(intArrayOf(0, 1), names, listOf("2.jpg", "1.jpg")), move = false)
        assertEquals(listOf(1, 2), sizes.read("id:${c.hex}", listOf("2.jpg", "1.jpg"))!![1]!!.toList())
        assertNull(BookFactsCache(context).get(c))
        // Every page the same, in order: the facts come along, and moved, the old book keeps none.
        val d = BookId(0xdd)
        BookCaches.carry(context, a, d, intArrayOf(0, 1), BookCaches.SamePages(intArrayOf(0, 1), names, names), move = true)
        assertEquals(BookFacts(2, 2014), BookFactsCache(context).get(d))
        assertNull(BookFactsCache(context).get(a))
        assertNull(sizes.read("id:${a.hex}", names))
    }

    @Test
    fun clearingTheCacheEmptiesEveryStoreAndKeepsLibraryData() {
        val library = Library(context)
        library.setRead(BookRef(a, "/storage/emulated/0/Comics/a.cbz"), true)
        BookFactsCache(context).put(a, BookFacts(10, 0))
        val book = File(tmp.newFolder("Shelf"), "one.cbz").apply { writeBytes(ByteArray(300)) }
        bookId(book)
        for (dir in perBook + listOf(Thumbs.dirIn(cache), RemoteCache.dirIn(cache), Listings.dirIn(cache))) fileOf(dir, a)
        assertTrue(BookCaches.bytes(context) >= 7 * 4096)
        BookCaches.clear(context)
        for (dir in perBook + listOf(Thumbs.dirIn(cache), RemoteCache.dirIn(cache), Listings.dirIn(cache))) {
            assertTrue(dir.list().isNullOrEmpty())
        }
        assertNull(BookFactsCache(context).get(a))
        assertNull(BookIds.known(book.path, book.length(), book.lastModified()))
        assertTrue(library.isRead(a))
    }
}
