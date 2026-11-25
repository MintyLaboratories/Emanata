package minty.emanata.ui

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import minty.emanata.compact.PageWork
import minty.emanata.data.Library
import minty.emanata.data.Prefs
import minty.emanata.files.Container
import minty.emanata.files.ConvertRates
import minty.emanata.files.FileWork
import minty.emanata.reader.ImageKind
import minty.emanata.testing.FakeImages
import minty.emanata.testing.FakeJxl
import org.junit.After
import org.junit.Assert.assertEquals
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
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** What Convert jobs know of books before the work, without decoding: their marks, their pages, and the plan a job tells. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FileJobsPlanTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var jobs: FileJobs

    /** A JPEG as far as its first bytes tell, that the fake transcode shrinks. */
    private fun jpeg(n: Int) = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), n.toByte()) + ByteArray(3000) { (it % 40 + n).toByte() }

    /** A job's progress that keeps the plan it is told. */
    private class Heard : FileWork.Progress {
        var plan: List<Double>? = null
        override val alive = true

        override fun report(done: Long, total: Long) = Unit

        override fun stoppable(can: Boolean) = Unit

        override fun item(name: String, index: Int, count: Int) = Unit

        override fun plan(seconds: List<Double>) {
            plan = seconds
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        ConvertRates.reset(context)
        jobs = FileJobs(context, Library(context), Prefs(context)) { PageWork(FakeJxl(), FakeImages()) }
    }

    @After
    fun tearDown() {
        ConvertRates.reset(context)
    }

    private fun zip(name: String, entries: Map<String, ByteArray>): File {
        val file = File(tmp.root, name)
        ZipOutputStream(file.outputStream()).use { out ->
            for ((path, data) in entries) {
                // Stored, as a book of JPEGs nearly is.
                out.putNextEntry(ZipEntry(path).apply {
                    method = ZipEntry.STORED
                    size = data.size.toLong()
                    crc = CRC32().apply { update(data) }.value
                })
                out.write(data)
                out.closeEntry()
            }
        }
        return file
    }

    private fun book(name: String, pages: Int = 3) = zip(name, (1..pages).associate { "$it.jpg" to jpeg(it) })

    /** [count] copies of one book compacted, each with Compact's mark. */
    private fun compacted(count: Int): List<String> {
        val source = book("Source.cbz")
        val done = jobs.rebuild(listOf(source.absolutePath), FileJobs.Rebuild.COMPACT, inPlace = true, skipHidden = false, chosen = jobs.look(listOf(source.absolutePath)), p = Heard())
        assertEquals(1, (done as FileWork.Outcome.Done).count)
        return List(count) { i -> source.copyTo(File(tmp.root, "Done $i.cbz")).absolutePath }
    }

    @Test
    fun marksAreReadPastTheListingBudget() {
        val paths = compacted(200)
        val plans = jobs.planBooks(paths, FileJobs.Rebuild.COMPACT, listMs = 0)
        assertTrue(plans.all { it.done == true })
        assertTrue(plans.all { it.pages == null })
        // Compacted is not repacked.
        assertTrue(jobs.planBooks(paths.take(3), FileJobs.Rebuild.REPACK, listMs = 0).all { it.done == false })
    }

    @Test
    fun booksPastTheMarkBudgetAreUnread() {
        val plans = jobs.planBooks(compacted(3), FileJobs.Rebuild.COMPACT, markMs = -1)
        assertTrue(plans.all { it.done == null && it.pages == null && it.bytes > 0 })
    }

    @Test
    fun aListedBookTellsItsPagesAndABookOfArchivesIsLeftAlone() {
        val loose = zip("Loose.cbz", mapOf("1.jpg" to jpeg(1), "2.png" to ByteArray(10), "ComicInfo.xml" to ByteArray(5)))
        val nested = zip("Nested.cbz", mapOf("part 1.cbz" to ByteArray(4000)))
        val plans = jobs.planBooks(listOf(loose.absolutePath, nested.absolutePath), FileJobs.Rebuild.COMPACT)
        assertEquals(listOf(ImageKind.JPEG, ImageKind.PNG), plans[0].pages!!.map { it.format })
        assertEquals(false, plans[0].done)
        assertEquals(Container.ZIP, plans[0].container)
        assertEquals(true, plans[1].done)
    }

    @Test
    fun foldersPastTheListingBudgetTellTheirBytesOnlyAndNothingIsReadOnceNotAlive() {
        val dir = tmp.newFolder("Loose")
        repeat(3) { File(dir, "0$it.jpg").writeBytes(jpeg(it)) }
        val listed = jobs.planFolders(listOf(dir.absolutePath)).single()
        assertEquals(3, listed.pages!!.size)
        val past = jobs.planFolders(listOf(dir.absolutePath), listMs = -1).single()
        assertNull(past.pages)
        assertEquals(listed.bytes, past.bytes)
        val stopped = jobs.planFolders(listOf(dir.absolutePath), alive = { false }).single()
        assertNull(stopped.done)
        assertEquals(0L, stopped.bytes)
    }

    @Test
    fun nothingIsReadOnceNotAlive() {
        val plans = jobs.planBooks(listOf(book("A.cbz").absolutePath), FileJobs.Rebuild.COMPACT, alive = { false })
        assertNull(plans.single().done)
        assertNull(plans.single().pages)
    }

    @Test
    fun aRebuildTellsItsPlanAndKeepsWhatItLearned() {
        val paths = listOf(book("A.cbz").absolutePath, book("B.cbz").absolutePath)
        val heard = Heard()
        jobs.rebuild(paths, FileJobs.Rebuild.COMPACT, inPlace = false, skipHidden = false, chosen = jobs.look(paths), p = heard)
        assertEquals(2, heard.plan!!.size)
        assertTrue(heard.plan!!.all { it > 0 })
        assertTrue(File(context.noBackupFilesDir, "convert_rates.txt").readText().isNotBlank())
    }
}
