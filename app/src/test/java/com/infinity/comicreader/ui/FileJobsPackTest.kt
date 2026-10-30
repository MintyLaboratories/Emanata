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
