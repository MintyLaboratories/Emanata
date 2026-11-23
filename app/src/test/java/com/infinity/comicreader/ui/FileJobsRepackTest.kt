package com.infinity.comicreader.ui

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.compact.OutputMarks
import com.infinity.comicreader.compact.PageWork
import com.infinity.comicreader.data.BookIds
import com.infinity.comicreader.data.Bookmark
import com.infinity.comicreader.data.HistoryItem
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.files.FileWork
import com.infinity.comicreader.reader.Fixtures
import com.infinity.comicreader.reader.Fixtures.entry
import com.infinity.comicreader.testing.FakeImages
import com.infinity.comicreader.testing.FakeJxl
import com.infinity.comicreader.testing.bookId
import com.infinity.comicreader.testing.ref
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Repack as CBZ as queued, and the marks every rebuild leaves on its new file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FileJobsRepackTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var library: Library
    private lateinit var prefs: Prefs
    private lateinit var jobs: FileJobs
    private val jxl = FakeJxl()

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

    /** A JPEG as far as its first bytes tell, that the fake transcode shrinks. */
    private fun jpeg(n: Int) = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), n.toByte()) + ByteArray(3000) { (it % 40 + n).toByte() }

    private val info = entry("ComicInfo.xml", "<ComicInfo><Title>Sample</Title></ComicInfo>".toByteArray())
    private val files = arrayOf(entry("01.jpg", jpeg(1)), entry("02.jpg", jpeg(2)), entry("10.jpg", jpeg(3)), info, entry("Thumbs.db", ByteArray(40) { 9 }))

    private fun run(book: File, how: FileJobs.Rebuild, inPlace: Boolean, skipHidden: Boolean = false): FileWork.Outcome =
        jobs.rebuild(listOf(book.absolutePath), how, inPlace, skipHidden, jobs.look(listOf(book.absolutePath)), Going)

    private fun entries(zip: File): List<Pair<String, ByteArray>> = ZipFile(zip).use { z ->
        z.entries().toList().map { e ->
            assertEquals(ZipEntry.STORED, e.method)
            e.name to z.getInputStream(e).readBytes()
        }
    }

    private fun assertRepackedVerbatim(cbz: File) {
        val got = entries(cbz)
        // Every file as it was, in natural order, OS junk left out.
        assertEquals(listOf("01.jpg", "02.jpg", "10.jpg", "ComicInfo.xml"), got.map { it.first })
        for ((name, bytes) in got) assertArrayEquals(name, files.first { it.first == name }.second, bytes)
        assertTrue(OutputMarks.verified(cbz, BookIds.xxh3).repacked)
    }

    @Test
    fun aSolidRarRepacksBesideItsBookByteForByte() {
        val book = File(tmp.root, "Book.cbr").apply { writeBytes(Fixtures.rar5Bytes(*files, solid = true)) }
        val outcome = run(book, FileJobs.Rebuild.REPACK, inPlace = false) as FileWork.Outcome.Done
        val cbz = File(tmp.root, "Book.cbz")
        assertEquals(cbz.absolutePath, outcome.path)
        assertTrue(book.exists())
        assertTrue(outcome.gone.isEmpty())
        assertRepackedVerbatim(cbz)
    }

    @Test
    fun aSolidRar5RepacksInPlaceAndItsDataFollows() {
        val book = File(tmp.root, "Book.cbr").apply { writeBytes(Fixtures.rar5Bytes(*files, solid = true)) }
        val old = ref(book)
        library.setFavorite(old, true)
        library.setHiddenPages(old, setOf(1), 3)
        library.setIgnoreSeams(old, true)
        library.addHistory(HistoryItem(book.absolutePath, book.name, 2, 3, 5, old.id))
        library.addBookmark(Bookmark(book.absolutePath, book.name, 1, "note", 5, old.id))
        val outcome = run(book, FileJobs.Rebuild.REPACK, inPlace = true) as FileWork.Outcome.Done
        val cbz = File(tmp.root, "Book.cbz")
        assertEquals(listOf(book.absolutePath), outcome.gone)
        assertFalse(book.exists())
        assertRepackedVerbatim(cbz)
        val now = bookId(cbz)
        assertTrue(library.isFavorite(now))
        assertEquals(setOf(1), library.hiddenPages(now))
        assertTrue(library.ignoresSeams(now))
        assertEquals(2, library.historyFor(now)!!.page)
        assertEquals(cbz.absolutePath, library.historyFor(now)!!.path)
        assertEquals(listOf(1), library.bookmarksFor(now).map { it.page })
        assertFalse(library.hasData(old.id))
    }

    @Test
    fun aSolidSevenZipRepacksInPlace() {
        val book = Fixtures.solidSevenZip(File(tmp.root, "Book.cb7"), *files)
        val old = ref(book)
        library.setRead(old, true)
        run(book, FileJobs.Rebuild.REPACK, inPlace = true) as FileWork.Outcome.Done
        val cbz = File(tmp.root, "Book.cbz")
        assertFalse(book.exists())
        assertRepackedVerbatim(cbz)
        assertTrue(library.isRead(bookId(cbz)))
    }

    @Test
    fun aRepeatedNameKeepsBothFilesWithTheirBytes() {
        val book = File(tmp.root, "Two.cbr").apply {
            writeBytes(Fixtures.rar5Bytes(entry("a/01.jpg", jpeg(1)), entry("b/01.jpg", jpeg(2)), solid = true))
        }
        run(book, FileJobs.Rebuild.REPACK, inPlace = false) as FileWork.Outcome.Done
        val got = entries(File(tmp.root, "Two.cbz"))
        assertEquals(2, got.map { it.first }.distinct().size)
        assertArrayEquals(jpeg(1), got[0].second)
        assertArrayEquals(jpeg(2), got[1].second)
    }

    @Test
    fun anotherNameBesideWhenTheCbzNameIsTaken() {
        val book = File(tmp.root, "Book.cbr").apply { writeBytes(Fixtures.rar5Bytes(*files, solid = true)) }
        val taken = File(tmp.root, "Book.cbz").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val outcome = run(book, FileJobs.Rebuild.REPACK, inPlace = false) as FileWork.Outcome.Done
        assertEquals(File(tmp.root, "Book (repacked).cbz").absolutePath, outcome.path)
        assertArrayEquals(byteArrayOf(1, 2, 3), taken.readBytes())
    }

    private fun cbz(name: String, comment: String? = null): File {
        val f = File(tmp.root, name)
        ZipOutputStream(f.outputStream()).use { zip ->
            // Stored, as a book of JPEGs nearly is: compacting makes it smaller.
            for (i in 1..2) {
                val data = jpeg(i)
                zip.putNextEntry(
                    ZipEntry("$i.jpg").apply {
                        method = ZipEntry.STORED
                        size = data.size.toLong()
                        crc = java.util.zip.CRC32().apply { update(data) }.value
                    }
                )
                zip.write(data)
                zip.closeEntry()
            }
            comment?.let { zip.setComment(it) }
        }
        return f
    }

    @Test
    fun compactMarksItsOutputKeepingTheBooksComment() {
        val book = cbz("Book.cbz", "From the scanner")
        run(book, FileJobs.Rebuild.COMPACT, inPlace = true) as FileWork.Outcome.Done
        val comment = ZipFile(book).use { it.comment }
        assertTrue(comment, comment.startsWith("From the scanner\n[i: Emanata:compact:v1 | "))
        assertEquals("Emanata", OutputMarks.verified(book, BookIds.xxh3).compactedBy)
    }

    @Test
    fun aBookAlreadyCompactedIsLeftAloneButMadeCompatible() {
        val book = cbz("Book.cbz")
        run(book, FileJobs.Rebuild.COMPACT, inPlace = true) as FileWork.Outcome.Done
        val compacted = book.readBytes()
        val again = run(book, FileJobs.Rebuild.COMPACT, inPlace = true) as FileWork.Outcome.Done
        assertEquals(0, again.count)
        assertEquals(1, again.summary!!.already)
        assertArrayEquals(compacted, book.readBytes())
        run(book, FileJobs.Rebuild.COMPATIBLE, inPlace = true) as FileWork.Outcome.Done
        val done = OutputMarks.verified(book, BookIds.xxh3)
        assertTrue(done.compatible)
        assertNull("making compatible after it changed the pages", done.compactedBy)
        val blocks = OutputMarks.blocks(ZipFile(book).use { it.comment })
        assertEquals(1, blocks.size)
        assertEquals("Emanata:compact:v1, Emanata:compatible:v1", blocks[0].entries.joinToString(", "))
    }

    @Test
    fun skippingHiddenPagesMarksTheOutputPartial() {
        val book = cbz("Book.cbz")
        library.setHiddenPages(ref(book), setOf(1), 2)
        run(book, FileJobs.Rebuild.COMPACT, inPlace = true, skipHidden = true) as FileWork.Outcome.Done
        assertTrue(ZipFile(book).use { it.comment }.startsWith("[i: Emanata:compact-partial:v1 | "))
        assertEquals("Emanata", OutputMarks.verified(book, BookIds.xxh3).compactedBy)
    }

    @Test
    fun repackingNeedsNoLibjxl() {
        val none = FileJobs(context, library, prefs) { null }
        val book = File(tmp.root, "Book.cbr").apply { writeBytes(Fixtures.rar5Bytes(*files, solid = true)) }
        val outcome = none.rebuild(listOf(book.absolutePath), FileJobs.Rebuild.REPACK, false, false, none.look(listOf(book.absolutePath)), Going)
        assertTrue(outcome is FileWork.Outcome.Done)
    }
}
