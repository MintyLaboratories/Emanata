package minty.emanata.ui

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import minty.emanata.compact.Handovers
import minty.emanata.compact.JxlCodec
import minty.emanata.compact.PageWork
import minty.emanata.compact.Pixels
import minty.emanata.data.BookId
import minty.emanata.data.BookIds
import minty.emanata.data.Bookmark
import minty.emanata.data.HistoryItem
import minty.emanata.data.Library
import minty.emanata.data.OpenBooks
import minty.emanata.data.Prefs
import minty.emanata.files.FileWork
import minty.emanata.net.ChangeRefused
import minty.emanata.testing.FakeImages
import minty.emanata.testing.FakeJxl
import minty.emanata.testing.bookId
import minty.emanata.testing.ref
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Compact and Make compatible as queued: each book rebuilt, and what the library keeps for it follows its new file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FileJobsRebuildTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var library: Library
    private lateinit var prefs: Prefs
    private lateinit var jobs: FileJobs
    private val jxl = FakeJxl()

    /** A JPEG as far as its first bytes tell, that the fake transcode shrinks. */
    private fun jpeg(n: Int) = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), n.toByte()) + ByteArray(3000) { (it % 40 + n).toByte() }

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
        library = Library(context)
        prefs = Prefs(context)
        jobs = FileJobs(context, library, prefs) { PageWork(jxl, FakeImages()) }
    }

    private fun sevenZip(name: String, pages: Int): File {
        val file = File(tmp.root, name)
        SevenZOutputFile(file).use { out ->
            // Stored, as a book of JPEGs nearly is.
            out.setContentCompression(SevenZMethod.COPY)
            for (i in 1..pages) {
                val data = jpeg(i)
                val e = out.createArchiveEntry(File(tmp.root, "x"), "$i.jpg")
                e.size = data.size.toLong()
                out.putArchiveEntry(e)
                out.write(data)
                out.closeArchiveEntry()
            }
        }
        return file
    }

    private fun rebuild(book: File, compatible: Boolean = false, inPlace: Boolean = true, skipHidden: Boolean = false): FileWork.Outcome =
        jobs.rebuild(listOf(book.absolutePath), compatible, inPlace, skipHidden, jobs.look(listOf(book.absolutePath)), Going)

    @Test
    fun aBookCompactedInPlaceTakesItsDataAlong() {
        val book = sevenZip("Book.cb7", 3)
        val old = ref(book)
        library.setFavorite(old, true)
        library.setHiddenPages(old, setOf(1), 3)
        library.addHistory(HistoryItem(book.absolutePath, book.name, 1, 2, 5, old.id))
        library.addBookmark(Bookmark(book.absolutePath, book.name, 1, "note", 5, old.id))
        val outcome = rebuild(book) as FileWork.Outcome.Done
        val cbz = File(tmp.root, "Book.cbz")
        assertEquals(cbz.absolutePath, outcome.path)
        assertEquals(listOf(book.absolutePath), outcome.gone)
        assertEquals(1, outcome.count)
        assertTrue(outcome.summary!!.saved > 0)
        assertFalse(book.exists())
        val now = bookId(cbz)
        assertTrue(library.isFavorite(now))
        assertFalse(library.isFavorite(old.id))
        assertEquals(setOf(1), library.hiddenPages(now))
        assertEquals(1, library.historyFor(now)!!.page)
        assertEquals(cbz.absolutePath, library.historyFor(now)!!.path)
        assertEquals(listOf(1), library.bookmarksFor(now).map { it.page })
        assertFalse(library.hasData(old.id))
        assertTrue(jobs.handovers.entries().isEmpty())
    }

    @Test
    fun skippingHiddenPagesLeavesThemOutAndKeepsWhatWasShown() {
        val book = sevenZip("Book.cb7", 3)
        val old = ref(book)
        library.setHiddenPages(old, setOf(1), 3)
        // Shown page 1 is book page 2.
        library.addHistory(HistoryItem(book.absolutePath, book.name, 1, 2, 5, old.id))
        rebuild(book, skipHidden = true) as FileWork.Outcome.Done
        val cbz = File(tmp.root, "Book.cbz")
        assertEquals(listOf("1.jxl", "3.jxl"), ZipFile(cbz).use { z -> z.entries().toList().map { it.name } })
        val now = bookId(cbz)
        assertTrue(library.hiddenPages(now).isEmpty())
        assertEquals(1, library.historyFor(now)!!.page)
    }

    @Test
    fun besideTheBookTheNewFileTakesTheDataAndTheBookStays() {
        val book = sevenZip("Book.cb7", 2)
        val old = ref(book)
        library.setRead(old, true)
        val outcome = rebuild(book, inPlace = false) as FileWork.Outcome.Done
        assertTrue(book.exists())
        assertTrue(outcome.gone.isEmpty())
        val now = bookId(File(outcome.path!!))
        assertTrue(library.isRead(now))
        assertFalse(library.isRead(old.id))
    }

    @Test
    fun makeCompatibleGivesTheJpegsBackAndTheBookItsIdAgain() {
        val book = sevenZip("Book.cb7", 2)
        val original = ref(book)
        library.setRead(original, true)
        rebuild(book)
        val cbz = File(tmp.root, "Book.cbz")
        val outcome = rebuild(cbz, compatible = true) as FileWork.Outcome.Done
        assertEquals(1, outcome.count)
        ZipFile(cbz).use { z ->
            assertEquals(listOf("1.jpg", "2.jpg"), z.entries().toList().map { it.name })
            assertTrue(z.getInputStream(z.getEntry("2.jpg")).readBytes().contentEquals(jpeg(2)))
        }
        assertTrue(library.isRead(bookId(cbz)))
        // Nothing left to change.
        val again = rebuild(cbz, compatible = true) as FileWork.Outcome.Done
        assertEquals(0, again.count)
        assertEquals(1, again.summary!!.unchanged)
    }

    @Test
    fun whenALaterBookFailsTheSummaryStillNamesThePagesReencoded() {
        val pixels = Pixels(4, 3, 4, 8, ByteArray(4 * 3 * 4) { (it * 5).toByte() })
        val book = File(tmp.root, "Lossy.cbz")
        ZipOutputStream(book.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("1.jxl"))
            zip.write(jxl.lossy(pixels))
            zip.closeEntry()
        }
        val broken = File(tmp.root, "Broken.cbz").apply { writeBytes(ByteArray(100) { 7 }) }
        val paths = listOf(book.absolutePath, broken.absolutePath)
        val outcome = jobs.rebuild(paths, true, true, false, jobs.look(paths), Going) as FileWork.Outcome.Failed
        assertEquals(1, outcome.done)
        assertEquals(mapOf("Lossy.cbz" to listOf("1.webp")), outcome.summary!!.reencoded)
    }

    @Test
    fun pagesStillJpegXlAreNamedInTheSummary() {
        val pixels = Pixels(4, 3, 4, 8, ByteArray(4 * 3 * 4) { (it * 5).toByte() })
        val book = File(tmp.root, "Mixed.cbz")
        ZipOutputStream(book.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("1.jxl"))
            zip.write(jxl.lossy(pixels))
            zip.closeEntry()
            // A JPEG XL the codec cannot read.
            zip.putNextEntry(ZipEntry("2.jxl"))
            zip.write(FakeJxl.CONTAINER + ByteArray(20))
            zip.closeEntry()
        }
        val outcome = rebuild(book, compatible = true) as FileWork.Outcome.Done
        assertEquals(1, outcome.count)
        assertEquals(mapOf("Mixed.cbz" to listOf("2.jxl")), outcome.summary!!.failed)
    }

    @Test
    fun aBookOpenInTheReaderIsLeftAlone() {
        val book = sevenZip("Open.cb7", 2)
        val bytes = book.readBytes()
        OpenBooks.opened(book.absolutePath)
        try {
            val outcome = rebuild(book) as FileWork.Outcome.Failed
            assertEquals(ChangeRefused.Why.IN_READER, (outcome.error as ChangeRefused).why)
            assertTrue(book.readBytes().contentEquals(bytes))
        } finally {
            OpenBooks.closed(book.absolutePath)
        }
        assertTrue(rebuild(book) is FileWork.Outcome.Done)
    }

    @Test
    fun aBookOpenedWhileItsPagesConvertKeepsItsFile() {
        val book = sevenZip("Late.cb7", 2)
        val old = ref(book)
        library.setRead(old, true)
        // The reader opens the book while its pages convert.
        var opened = false
        val opens = object : JxlCodec by jxl {
            override fun transcode(jpeg: ByteArray, effort: Int): ByteArray? {
                synchronized(this) {
                    if (!opened) OpenBooks.opened(book.absolutePath)
                    opened = true
                }
                return jxl.transcode(jpeg, effort)
            }
        }
        val opening = FileJobs(context, library, prefs) { PageWork(opens, FakeImages()) }
        try {
            val outcome = opening.rebuild(listOf(book.absolutePath), false, true, false, opening.look(listOf(book.absolutePath)), Going)
            assertEquals(ChangeRefused.Why.IN_READER, ((outcome as FileWork.Outcome.Failed).error as ChangeRefused).why)
        } finally {
            OpenBooks.closed(book.absolutePath)
        }
        assertTrue(book.exists())
        assertFalse(File(tmp.root, "Late.cbz").exists())
        assertTrue(library.isRead(old.id))
    }

    @Test
    fun dataLeftBehindWhenTheAppStoppedFollowsOnRecovery() {
        val book = sevenZip("Stopped.cb7", 2)
        val old = ref(book)
        library.setFavorite(old, true)
        // The new file took the book's place; the app stopped before the data followed.
        val cbz = File(tmp.root, "Stopped.cbz")
        ZipOutputStream(cbz.outputStream()).use { zip ->
            for ((name, data) in listOf("1.jxl" to byteArrayOf(1, 2, 3), "2.jxl" to byteArrayOf(4, 5))) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        val new = BookIds.peek(cbz)
        jobs.handovers.add(Handovers.Entry(old.id, new, book.absolutePath, cbz.absolutePath, true, intArrayOf(0, 1), 2))
        book.delete()
        // One whose new file never took its place is let go.
        jobs.handovers.add(Handovers.Entry(old.id, BookId(42), book.absolutePath, File(tmp.root, "Never.cbz").absolutePath, true, intArrayOf(0, 1), 2))
        jobs.recover()
        assertTrue(library.isFavorite(new))
        assertFalse(library.isFavorite(old.id))
        assertTrue(jobs.handovers.entries().isEmpty())
    }

    @Test
    fun withoutLibjxlNothingIsTouched() {
        val book = sevenZip("Book.cb7", 1)
        val none = FileJobs(context, library, prefs) { null }
        val outcome = none.rebuild(listOf(book.absolutePath), false, true, false, none.look(listOf(book.absolutePath)), Going)
        assertTrue(outcome is FileWork.Outcome.Failed)
        assertTrue(book.exists())
    }
}
