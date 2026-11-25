package minty.emanata.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import minty.emanata.compact.Compactor
import minty.emanata.compact.Handovers
import minty.emanata.compact.PageWork
import minty.emanata.data.BookCaches
import minty.emanata.data.BookFacts
import minty.emanata.data.BookFactsCache
import minty.emanata.data.BookId
import minty.emanata.data.BookIds
import minty.emanata.data.Library
import minty.emanata.data.Prefs
import minty.emanata.data.ReadingDirection
import minty.emanata.files.FileWork
import minty.emanata.reader.DirectionDetection
import minty.emanata.reader.Fixtures
import minty.emanata.reader.Fixtures.entry
import minty.emanata.reader.PageSeams
import minty.emanata.reader.PageSizes
import minty.emanata.reader.PreviewStore
import minty.emanata.reader.SpreadSeams.Seam
import minty.emanata.reader.SpreadSeams.Verdict
import minty.emanata.testing.FakeImages
import minty.emanata.testing.FakeJxl
import minty.emanata.testing.bookId
import minty.emanata.testing.ref
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.BitSet
import java.util.concurrent.Executor
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** What is cached for a rebuilt book's byte-identical pages follows them to its new file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FileJobsCachesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var library: Library
    private lateinit var jobs: FileJobs
    private lateinit var cache: File
    private val jxl = FakeJxl()
    private val direct = Executor { it.run() }

    private object Going : FileWork.Progress {
        override val alive = true

        override fun report(done: Long, total: Long) = Unit

        override fun stoppable(can: Boolean) = Unit

        override fun item(name: String, index: Int, count: Int) = Unit
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        cache = context.cacheDir
        cache.listFiles()?.forEach { it.deleteRecursively() }
        BookFactsCache.reset()
        library = Library(context)
        jobs = FileJobs(context, library, Prefs(context)) { PageWork(jxl, FakeImages()) }
    }

    /** A JPEG as far as its first bytes tell, that the fake transcode shrinks. */
    private fun jpeg(n: Int) = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), n.toByte()) + ByteArray(3000) { (it % 40 + n).toByte() }

    /** A page Compact never touches. */
    private fun gif(n: Int) = "GIF89a".toByteArray() + ByteArray(600) { (it * 7 + n).toByte() }

    /** A ZIP of [entries], each stored with its size and CRC. */
    private fun stored(name: String, vararg entries: Pair<String, ByteArray>): File {
        val file = File(tmp.root, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((path, data) in entries) {
                zip.putNextEntry(
                    ZipEntry(path).apply {
                        method = ZipEntry.STORED
                        size = data.size.toLong()
                        crc = CRC32().apply { update(data) }.value
                    }
                )
                zip.write(data)
                zip.closeEntry()
            }
        }
        return file
    }

    private fun run(book: File, how: FileJobs.Rebuild, skipHidden: Boolean = false): FileWork.Outcome.Done =
        jobs.rebuild(listOf(book.absolutePath), how, true, skipHidden, jobs.look(listOf(book.absolutePath)), Going) as FileWork.Outcome.Done

    /** The pages of [zip] as the reader names them. */
    private fun pagesOf(zip: File): List<String> = ZipFile(zip).use { z ->
        val names = z.entries().toList().map { it.name to it.size }
        Compactor.pagesOf(names).map { names[it].first }
    }

    private fun key(id: BookId) = "id:${id.hex}"

    private val sizes get() = PageSizes(PageSizes.dirIn(cache))
    private val seams get() = PageSeams(PageSeams.dirIn(cache))
    private val previews get() = PreviewStore(PreviewStore.dirIn(cache), direct)
    private val facts = BookFacts(3, 2014, DirectionDetection.Detected(ReadingDirection.RTL, DirectionDetection.Source.PUBLISHER))

    /** Page `i` of [names] is [size]`(i)` and seen; every seam is checked; each page has its preview; the book has [facts]. */
    private fun cacheFor(id: BookId, names: List<String>) {
        sizes.write(key(id), names, Array(names.size) { size(it) })
        val found = PageSeams.Found(names.size)
        for (i in 0 until names.size - 1) found.set(i, Verdict(Seam.MATCH, 1.0), Verdict(Seam.NO_MATCH, 1.0))
        seams.write(key(id), names, found)
        seams.writeSeen(key(id), names, BitSet().apply { set(0, names.size) })
        val bitmap = Bitmap.createBitmap(8, 12, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        for (i in names.indices) previews.put(PreviewStore.keyOf(key(id), i, names[i]), bitmap)
        BookFactsCache(context).put(id, facts)
    }

    private fun size(i: Int) = intArrayOf(100 + i, 150)

    private fun hasPreview(id: BookId, i: Int, name: String) = previews.has(PreviewStore.keyOf(key(id), i, name))

    @Test
    fun aRepackedCbzKeepsEveryCacheUnderItsNewId() {
        val old = listOf("Book/01.jpg", "Book/02.jpg", "Book/10.jpg")
        val book = Fixtures.zip(File(tmp.root, "Book.cbz"), *old.mapIndexed { i, n -> entry(n, jpeg(i)) }.toTypedArray())
        val id = bookId(book)
        cacheFor(id, old)
        run(book, FileJobs.Rebuild.REPACK)
        val now = bookId(book)
        val names = pagesOf(book)
        assertEquals(3, names.size)
        val read = sizes.read(key(now), names)!!
        for (i in 0..2) assertArrayEquals(size(i), read[i])
        val found = seams.read(key(now), names)!!
        assertTrue(found.checked(0) && found.checked(1))
        assertEquals(Seam.MATCH, found.of(rightToLeft = false)[1]!!.seam)
        assertEquals(BitSet().apply { set(0, 3) }, seams.readSeen(key(now), names))
        for (i in 0..2) assertTrue(hasPreview(now, i, names[i]))
        assertEquals(facts, BookFactsCache(context).get(now))
        // Moved: the old book's file was replaced.
        assertNull(sizes.read(key(id), old))
        assertFalse(hasPreview(id, 0, old[0]))
        assertNull(BookFactsCache(context).get(id))
    }

    @Test
    fun compactingDropsOnlyWhatWasCachedForTheReencodedPage() {
        val old = listOf("1.jpg", "2.gif", "3.gif")
        val book = stored("Book.cbz", "1.jpg" to jpeg(1), "2.gif" to gif(2), "3.gif" to gif(3))
        val id = bookId(book)
        cacheFor(id, old)
        run(book, FileJobs.Rebuild.COMPACT)
        val now = bookId(book)
        val names = pagesOf(book)
        assertEquals(listOf("1.jxl", "2.gif", "3.gif"), names)
        val read = sizes.read(key(now), names)!!
        assertNull(read[0])
        assertArrayEquals(size(1), read[1])
        assertArrayEquals(size(2), read[2])
        val found = seams.read(key(now), names)!!
        assertFalse(found.checked(0))
        assertTrue(found.checked(1))
        assertEquals(BitSet().apply { set(1, 3) }, seams.readSeen(key(now), names))
        assertFalse(hasPreview(now, 0, names[0]))
        assertTrue(hasPreview(now, 2, names[2]))
        // Not every page the same: the facts are read anew.
        assertNull(BookFactsCache(context).get(now))
        assertNull(sizes.read(key(id), old))
    }

    @Test
    fun skippingHiddenPagesRemapsWhatWasCached() {
        val old = listOf("1.gif", "2.gif", "3.gif")
        val book = stored("Book.cbz", "1.gif" to gif(1), "2.gif" to gif(2), "3.gif" to gif(3))
        val id = bookId(book)
        library.setHiddenPages(ref(book), setOf(1), 3)
        cacheFor(id, old)
        run(book, FileJobs.Rebuild.COMPACT, skipHidden = true)
        val now = bookId(book)
        val names = pagesOf(book)
        assertEquals(listOf("1.gif", "3.gif"), names)
        val read = sizes.read(key(now), names)!!
        assertArrayEquals(size(0), read[0])
        assertArrayEquals(size(2), read[1])
        // Pages 1 and 3 were never next to each other.
        assertFalse(seams.read(key(now), names)?.checked(0) ?: false)
        assertEquals(BitSet().apply { set(0, 2) }, seams.readSeen(key(now), names))
        assertTrue(hasPreview(now, 1, "3.gif"))
        assertNull(BookFactsCache(context).get(now))
    }

    @Test
    fun aBookWhoseReaderKnowsNoCrcCarriesNothing() {
        val old = listOf("1.gif", "2.gif")
        val book = Fixtures.sevenZip(File(tmp.root, "Book.cb7"), "1.gif" to gif(1), "2.gif" to gif(2))
        val id = bookId(book)
        cacheFor(id, old)
        run(book, FileJobs.Rebuild.REPACK)
        val cbz = File(tmp.root, "Book.cbz")
        val now = bookId(cbz)
        val names = pagesOf(cbz)
        assertEquals(old, names)
        assertNull(sizes.read(key(now), names))
        assertNull(seams.read(key(now), names))
        assertFalse(hasPreview(now, 0, names[0]))
        assertNull(BookFactsCache(context).get(now))
        // The old book went, and its caches with it.
        assertNull(sizes.read(key(id), old))
    }

    @Test
    fun aCopyElsewhereKeepsTheOldCaches() {
        val old = listOf("1.gif", "2.gif")
        val book = stored("Book.cbz", "1.gif" to gif(1), "2.gif" to gif(2))
        val copy = File(tmp.newFolder("Other"), "Book.cbz").apply { writeBytes(book.readBytes()) }
        val id = bookId(book)
        assertEquals(id, bookId(copy))
        cacheFor(id, old)
        run(book, FileJobs.Rebuild.REPACK)
        val now = bookId(book)
        val names = pagesOf(book)
        assertArrayEquals(size(1), sizes.read(key(now), names)!![1])
        assertTrue(hasPreview(now, 1, names[1]))
        assertEquals(facts, BookFactsCache(context).get(now))
        // Copied, not moved.
        assertArrayEquals(size(1), sizes.read(key(id), old)!![1])
        assertTrue(seams.read(key(id), old)!!.checked(0))
        assertTrue(hasPreview(id, 1, old[1]))
        assertNotNull(BookFactsCache(context).get(id))
    }

    /** The old book [id] of pages [old] gone, its rebuilt file [cbz] in its place holding them as [names]; the app stopped before its data followed. */
    private fun stoppedBeforeFollowing(book: File, cbz: File, id: BookId, old: List<String>, names: List<String>) {
        val new = BookIds.peek(cbz)
        val same = BookCaches.SamePages(intArrayOf(0, 1), old, names)
        jobs.handovers.add(Handovers.Entry(id, new, book.absolutePath, cbz.absolutePath, true, intArrayOf(0, 1), 2, same))
        book.delete()
    }

    @Test
    fun cachesFollowOnRecoveryWhenTheAppStoppedBetweenSwapAndFollow() {
        val old = listOf("a/1.gif", "a/2.gif")
        val book = Fixtures.zip(File(tmp.root, "Stopped.cbz"), "a/1.gif" to gif(1), "a/2.gif" to gif(2))
        val id = bookId(book)
        library.setFavorite(ref(book), true)
        cacheFor(id, old)
        val cbz = stored("Stopped (repacked).cbz", "1.gif" to gif(1), "2.gif" to gif(2))
        val names = pagesOf(cbz)
        stoppedBeforeFollowing(book, cbz, id, old, names)
        jobs.recover()
        val now = bookId(cbz)
        assertTrue(library.isFavorite(now))
        assertArrayEquals(size(1), sizes.read(key(now), names)!![1])
        assertTrue(seams.read(key(now), names)!!.checked(0))
        assertTrue(hasPreview(now, 0, names[0]))
        assertEquals(facts, BookFactsCache(context).get(now))
        assertNull(sizes.read(key(id), old))
        assertTrue(jobs.handovers.entries().isEmpty())
    }

    @Test
    fun cachesFollowOnRecoveryForABookTheLibraryKeepsNothingFor() {
        val old = listOf("1.gif", "2.gif")
        val book = stored("Plain.cbz", "1.gif" to gif(1), "2.gif" to gif(2))
        val id = bookId(book)
        cacheFor(id, old)
        val cbz = stored("Plain (repacked).cbz", "1.gif" to gif(1), "2.gif" to gif(2), "ComicInfo.xml" to "<ComicInfo/>".toByteArray())
        stoppedBeforeFollowing(book, cbz, id, old, pagesOf(cbz))
        jobs.recover()
        val now = bookId(cbz)
        assertArrayEquals(size(0), sizes.read(key(now), old)!![0])
        assertNull(sizes.read(key(id), old))
        assertNull(BookFactsCache(context).get(id))
    }
}
