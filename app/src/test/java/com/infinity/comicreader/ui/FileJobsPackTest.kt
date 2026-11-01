package com.infinity.comicreader.ui

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.compact.PageWork
import com.infinity.comicreader.data.BookIds
import com.infinity.comicreader.data.Bookmark
import com.infinity.comicreader.data.FolderLayout
import com.infinity.comicreader.data.HistoryItem
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.OpenBooks
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ReaderLayout
import com.infinity.comicreader.files.FileProblem
import com.infinity.comicreader.files.FileWork
import com.infinity.comicreader.net.ChangeRefused
import com.infinity.comicreader.testing.FakeImages
import com.infinity.comicreader.testing.FakeJxl
import com.infinity.comicreader.testing.bookId
import com.infinity.comicreader.testing.ref
import org.junit.Assert.assertArrayEquals
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Pack as queued: a folder of loose images becomes a `.cbz` beside it, takes its data along, and goes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FileJobsPackTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var library: Library
    private lateinit var prefs: Prefs
    private lateinit var jobs: FileJobs
    private val jxl = FakeJxl()

    /** A JPEG as far as its first bytes tell, that the fake transcode shrinks. */
    private fun jpeg(n: Int) = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), n.toByte()) + ByteArray(3000) { (it % 40 + n).toByte() }

    private open class Going : FileWork.Progress {
        override val alive get() = true

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

    private fun folder(name: String, pages: Int): File {
        val dir = File(tmp.root, name).apply { mkdirs() }
        for (i in 1..pages) File(dir, "$i.jpg").writeBytes(jpeg(i))
        return dir
    }

    private fun pack(vararg plans: FileJobs.PackPlan, compacted: Boolean = false, p: FileWork.Progress = Going()): FileWork.Outcome =
        jobs.pack(plans.toList(), compacted, jobs.look(plans.map { it.folder }), p)

    private fun plan(dir: File, name: String = "${dir.name}.cbz") = FileJobs.PackPlan(dir.absolutePath, name)

    private fun names(file: File) = ZipFile(file).use { z -> z.entries().toList().map { it.name } }

    @Test
    fun aFolderBecomesABookBesideItWithItsDataAndGoes() {
        val dir = folder("Loose", 3)
        File(dir, "Thumbs.db").writeBytes(byteArrayOf(1))
        val images = (1..3).map { ref(File(dir, "$it.jpg")) }
        val bytes = (1..3).map { File(dir, "$it.jpg").readBytes() }
        library.toggleFavoriteFolder(dir.absolutePath)
        library.setFolderLayout(dir.absolutePath, FolderLayout.Direction(ReaderLayout.DIR_RTL))
        library.setFolderTallStrip(dir.absolutePath, false)
        library.setReadMany(images, true)
        library.addHistory(HistoryItem(images[1].path, "2.jpg", 0, 1, 100, images[1].id))
        library.addBookmark(Bookmark(images[2].path, "3.jpg", 0, "note", 50, images[2].id))
        library.setHiddenPages(images[0], setOf(0), 1)
        val outcome = pack(plan(dir)) as FileWork.Outcome.Done
        val cbz = File(tmp.root, "Loose.cbz")
        assertEquals(cbz.absolutePath, outcome.path)
        assertEquals(listOf(dir.absolutePath), outcome.gone)
        assertEquals(1, outcome.count)
        assertFalse(dir.exists())
        assertEquals(listOf("1.jpg", "2.jpg", "3.jpg"), names(cbz))
        ZipFile(cbz).use { z ->
            assertTrue(z.entries().toList().all { it.method == ZipEntry.STORED })
            for (i in 0..2) assertArrayEquals(bytes[i], z.getInputStream(z.getEntry("${i + 1}.jpg")).readBytes())
        }
        val book = bookId(cbz)
        assertTrue(library.isFavorite(book))
        assertFalse(library.isFavoriteFolder(dir.absolutePath))
        assertTrue(library.isRead(book))
        assertEquals(FolderLayout.Direction(ReaderLayout.DIR_RTL), library.bookLayout(book))
        assertEquals(false, library.tallStrip(book))
        assertEquals(setOf(0), library.hiddenPages(book))
        // Counted as shown: the first page is hidden.
        assertEquals(0, library.historyFor(book)!!.page)
        assertEquals(listOf(1), library.bookmarksFor(book).map { it.page })
        assertEquals(cbz.absolutePath, library.historyFor(book)!!.path)
        // The images' rows went with the folder.
        assertTrue(library.history().all { it.id == book })
        assertTrue(library.folderLayout(dir.absolutePath) == null)
    }

    @Test
    fun imagesNeverSeenTakeWhatTheirCopiesKeep() {
        val elsewhere = File(tmp.root, "Elsewhere").apply { mkdirs() }
        val copies = (1..2).map { File(elsewhere, "$it.jpg").apply { writeBytes(jpeg(it)) } }
        library.setReadMany(copies.map { ref(it) }, true)
        library.addHistory(HistoryItem(copies[1].absolutePath, "2.jpg", 0, 1, 100, ref(copies[1]).id))
        val dir = folder("Copy", 2)
        pack(plan(dir)) as FileWork.Outcome.Done
        val book = bookId(File(tmp.root, "Copy.cbz"))
        assertTrue(library.isRead(book))
        assertEquals(1, library.historyFor(book)!!.page)
    }

    @Test
    fun imagesCopiedElsewhereKeepTheirFavoriteAndBookmarks() {
        val elsewhere = File(tmp.root, "Elsewhere").apply { mkdirs() }
        val copy = ref(File(elsewhere, "2.jpg").apply { writeBytes(jpeg(2)) })
        val dir = folder("Loose", 3)
        val images = (1..3).map { ref(File(dir, "$it.jpg")) }
        library.setFavorite(images[1], true)
        library.setFavorite(images[2], true)
        library.addBookmark(Bookmark(images[1].path, "2.jpg", 0, "kept", 10, images[1].id))
        library.addBookmark(Bookmark(images[2].path, "3.jpg", 0, "moved", 20, images[2].id))
        pack(plan(dir)) as FileWork.Outcome.Done
        val book = bookId(File(tmp.root, "Loose.cbz"))
        assertTrue(library.isFavorite(book))
        // The copy's image is still a favorite, bookmarked, seen where it is; the other one's went to the book.
        assertTrue(library.isFavorite(copy.id))
        assertFalse(library.isFavorite(images[2].id))
        assertEquals(listOf("kept"), library.bookmarksFor(copy.id).map { it.note })
        assertEquals(copy.path, library.pathOf(copy.id))
        assertEquals(listOf(1 to "kept", 2 to "moved"), library.bookmarksFor(book).map { it.page to it.note })
    }

    @Test
    fun identicalImagesInTheFolderKeepTheirOwnPages() {
        val dir = folder("Loose", 2)
        // 3.jpg is 1.jpg again: its bookmark and history go to page 3.
        val third = File(dir, "3.jpg").apply { writeBytes(jpeg(1)) }
        val id = ref(third).id
        library.addBookmark(Bookmark(third.absolutePath, "3.jpg", 0, "third", 10, id))
        library.addHistory(HistoryItem(third.absolutePath, "3.jpg", 0, 1, 100, id))
        pack(plan(dir)) as FileWork.Outcome.Done
        val book = bookId(File(tmp.root, "Loose.cbz"))
        assertEquals(listOf(2), library.bookmarksFor(book).map { it.page })
        assertEquals(2, library.historyFor(book)!!.page)
    }

    @Test
    fun packCompactedMakesItsPagesSmaller() {
        val dir = folder("Loose", 2)
        val outcome = pack(plan(dir), compacted = true) as FileWork.Outcome.Done
        val cbz = File(tmp.root, "Loose.cbz")
        assertEquals(listOf("1.jxl", "2.jxl"), names(cbz))
        assertTrue(outcome.summary!!.saved > 0)
        assertFalse(dir.exists())
    }

    @Test
    fun aNameTakenSinceKeepsBoth() {
        val dir = folder("Loose", 1)
        File(tmp.root, "Loose.cbz").writeText("there")
        val outcome = pack(plan(dir)) as FileWork.Outcome.Done
        assertEquals(File(tmp.root, "Loose (2).cbz").absolutePath, outcome.path)
        assertEquals("there", File(tmp.root, "Loose.cbz").readText())
        assertEquals(listOf("Loose (2).cbz"), outcome.summary!!.keptBoth)
    }

    @Test
    fun replaceTakesThePlaceOfTheFileChosen() {
        val dir = folder("Loose", 1)
        val old = File(tmp.root, "Loose.cbz").apply { writeText("there") }
        val oldRef = ref(old)
        library.setFavorite(oldRef, true)
        val over = com.infinity.comicreader.files.DeviceSpace.stat(old.absolutePath)
        val outcome = pack(FileJobs.PackPlan(dir.absolutePath, "Loose.cbz", replace = true, over = over)) as FileWork.Outcome.Done
        assertEquals(old.absolutePath, outcome.path)
        assertEquals(listOf("1.jpg"), names(old))
        assertFalse(library.isFavorite(oldRef.id))
    }

    /** Progress that runs [then] once, at the first report. */
    private fun onFirstReport(then: () -> Unit) = object : Going() {
        private var told = false

        override fun report(done: Long, total: Long) {
            if (!told) then()
            told = true
        }
    }

    @Test
    fun aFileWrittenAtTheNameMeanwhileIsNeverReplacedAndBothAreKept() {
        val dir = folder("Loose", 2)
        val cbz = File(tmp.root, "Loose.cbz")
        val outcome = pack(plan(dir), p = onFirstReport { cbz.writeText("theirs") }) as FileWork.Outcome.Done
        assertEquals("theirs", cbz.readText())
        val kept = File(tmp.root, "Loose (2).cbz")
        assertEquals(kept.absolutePath, outcome.path)
        assertEquals(listOf("Loose (2).cbz"), outcome.summary!!.keptBoth)
        assertEquals(listOf("1.jpg", "2.jpg"), names(kept))
        assertFalse(dir.exists())
        // The folder's data follows the file where it went.
        assertEquals(kept.absolutePath, library.pathOf(bookId(kept)))
    }

    @Test
    fun replaceLeavesAFileChangedMeanwhile() {
        val dir = folder("Loose", 1)
        val old = File(tmp.root, "Loose.cbz").apply { writeText("there") }
        val over = com.infinity.comicreader.files.DeviceSpace.stat(old.absolutePath)
        val plan = FileJobs.PackPlan(dir.absolutePath, "Loose.cbz", replace = true, over = over)
        val outcome = pack(plan, p = onFirstReport { old.writeText("changed since") }) as FileWork.Outcome.Failed
        assertEquals(FileProblem.Kind.ALTERED, (outcome.error as FileProblem).kind)
        assertEquals("changed since", old.readText())
        assertTrue(dir.exists())
    }

    @Test
    fun aFolderHoldingMoreThanImagesNowIsLeftAlone() {
        val dir = folder("Loose", 2)
        val chosen = jobs.look(listOf(dir.absolutePath))
        File(dir, "notes.txt").writeText("x")
        val outcome = jobs.pack(listOf(plan(dir)), false, chosen, Going()) as FileWork.Outcome.Failed
        assertTrue(outcome.error is FileProblem)
        assertEquals(3, dir.list()!!.size)
        assertFalse(File(tmp.root, "Loose.cbz").exists())
    }

    @Test
    fun litterFoldersGoWithTheFolder() {
        val dir = folder("Mac", 2)
        File(dir, "__MACOSX").mkdirs()
        File(dir, "__MACOSX/._1.jpg").writeBytes(byteArrayOf(1))
        pack(plan(dir)) as FileWork.Outcome.Done
        assertEquals(listOf("1.jpg", "2.jpg"), names(File(tmp.root, "Mac.cbz")))
        assertFalse(dir.exists())
    }

    /** A folder of two images with unusual files: a dot-folder, a dot-file and an empty image. */
    private fun unusualFolder(): File {
        val dir = folder("Odd", 2)
        File(dir, ".thumbnails/sub").mkdirs()
        File(dir, ".thumbnails/sub/1.jpg").writeBytes(byteArrayOf(1))
        File(dir, ".cover.jpg").writeBytes(jpeg(9))
        File(dir, "0.jpg").writeBytes(ByteArray(0))
        return dir
    }

    private val unusualNames = listOf(".cover.jpg", ".thumbnails", "0.jpg")

    @Test
    fun unusualFilesLeftOutAreDeletedWithTheFolder() {
        val dir = unusualFolder()
        pack(FileJobs.PackPlan(dir.absolutePath, "Odd.cbz", unusual = unusualNames)) as FileWork.Outcome.Done
        assertEquals(listOf("1.jpg", "2.jpg"), names(File(tmp.root, "Odd.cbz")))
        assertFalse(dir.exists())
    }

    @Test
    fun unusualFilesPackedGoInAsFilesNeverPages() {
        val dir = unusualFolder()
        val images = (1..2).map { ref(File(dir, "$it.jpg")) }
        library.setReadMany(images, true)
        library.addBookmark(Bookmark(images[1].path, "2.jpg", 0, "note", 50, images[1].id))
        pack(FileJobs.PackPlan(dir.absolutePath, "Odd.cbz", unusual = unusualNames, unusualToo = true)) as FileWork.Outcome.Done
        val cbz = File(tmp.root, "Odd.cbz")
        assertEquals(listOf(".cover.jpg", ".thumbnails/sub/1.jpg", "0.jpg", "1.jpg", "2.jpg"), names(cbz))
        assertFalse(dir.exists())
        val book = bookId(cbz)
        assertTrue(library.isRead(book))
        // The second image is the second page: none of the unusual files counts.
        assertEquals(listOf(1), library.bookmarksFor(book).map { it.page })
    }

    @Test
    fun packCompactedKeepsWhatIsInDotFoldersAsItIs() {
        val dir = folder("Odd", 2)
        File(dir, ".thumbnails").mkdirs()
        val hidden = jpeg(7)
        File(dir, ".thumbnails/1.jpg").writeBytes(hidden)
        pack(FileJobs.PackPlan(dir.absolutePath, "Odd.cbz", unusual = listOf(".thumbnails"), unusualToo = true), compacted = true) as FileWork.Outcome.Done
        val cbz = File(tmp.root, "Odd.cbz")
        assertEquals(listOf(".thumbnails/1.jpg", "1.jxl", "2.jxl"), names(cbz))
        ZipFile(cbz).use { z -> assertArrayEquals(hidden, z.getInputStream(z.getEntry(".thumbnails/1.jpg")).readBytes()) }
    }

    @Test
    fun unusualFilesNotAskedAboutLeaveTheFolderAlone() {
        val dir = unusualFolder()
        val outcome = pack(FileJobs.PackPlan(dir.absolutePath, "Odd.cbz", unusual = listOf(".cover.jpg"))) as FileWork.Outcome.Failed
        assertEquals(FileProblem.Kind.ALTERED, (outcome.error as FileProblem).kind)
        assertTrue(File(dir, ".thumbnails/sub/1.jpg").exists())
        assertFalse(File(tmp.root, "Odd.cbz").exists())
    }

    @Test
    fun comicInfoGoesIntoTheBook() {
        val dir = folder("Info", 2)
        File(dir, "ComicInfo.xml").writeText("<ComicInfo><Title>A title</Title></ComicInfo>")
        pack(plan(dir)) as FileWork.Outcome.Done
        val cbz = File(tmp.root, "Info.cbz")
        assertEquals(listOf("1.jpg", "2.jpg", "ComicInfo.xml"), names(cbz))
        ZipFile(cbz).use { z -> assertEquals("<ComicInfo><Title>A title</Title></ComicInfo>", z.getInputStream(z.getEntry("ComicInfo.xml")).readBytes().decodeToString()) }
        assertFalse(dir.exists())
    }

    @Test
    fun aFileAddedMeanwhileLeavesTheFolderWhole() {
        val dir = folder("Loose", 2)
        val outcome = pack(plan(dir), p = onFirstReport { File(dir, "notes.txt").writeText("x") }) as FileWork.Outcome.Failed
        assertEquals(1, outcome.done)
        assertTrue(outcome.gone.isEmpty())
        assertEquals(listOf("1.jpg", "2.jpg"), names(File(tmp.root, "Loose.cbz")))
        assertEquals(setOf("1.jpg", "2.jpg", "notes.txt"), dir.list()!!.toSet())
    }

    @Test
    fun cancelingLeavesTheFolderAsItWas() {
        val dir = folder("Long", 12)
        val reports = AtomicInteger()
        val canceling = object : Going() {
            override val alive get() = reports.get() < 2

            override fun report(done: Long, total: Long) {
                reports.incrementAndGet()
            }
        }
        val outcome = pack(plan(dir), p = canceling)
        assertTrue(outcome is FileWork.Outcome.Canceled)
        assertEquals(12, dir.list()!!.size)
        assertEquals(listOf("Long"), tmp.root.list()!!.filter { it.startsWith("Long") })
    }

    @Test
    fun anImageOpenInTheReaderLeavesTheFolder() {
        val dir = folder("Open", 2)
        val image = File(dir, "1.jpg").absolutePath
        OpenBooks.opened(image)
        try {
            val outcome = pack(plan(dir)) as FileWork.Outcome.Failed
            assertEquals(ChangeRefused.Why.IN_READER, (outcome.error as ChangeRefused).why)
            assertTrue(dir.exists())
            assertFalse(File(tmp.root, "Open.cbz").exists())
        } finally {
            OpenBooks.closed(image)
        }
    }

    @Test
    fun severalFoldersPackOneAtATimePastOneThatFails() {
        val a = folder("A", 1)
        val b = folder("B", 1).also { File(it, "Sub").mkdirs() }
        val c = folder("C", 2)
        val outcome = pack(plan(a), plan(b), plan(c)) as FileWork.Outcome.Failed
        assertEquals(2, outcome.done)
        assertEquals(listOf(a.absolutePath, c.absolutePath), outcome.gone)
        assertTrue(b.exists())
        assertTrue(File(tmp.root, "C.cbz").exists())
        assertTrue(BookIds.find(File(tmp.root, "A.cbz")) != null)
    }
}
