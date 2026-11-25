package minty.emanata.ui

import android.app.Application
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ApplicationProvider
import minty.emanata.R
import minty.emanata.data.Library
import minty.emanata.data.Prefs
import minty.emanata.files.ConvertRates
import minty.emanata.files.FileWork
import minty.emanata.files.FileWorkService
import minty.emanata.files.WorkRates
import minty.emanata.testing.idleUntil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Convert actions' times: how long each takes in its dialog, and the time left in its notification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConvertTimesTest {
    private lateinit var context: Application
    private lateinit var controller: ActivityController<AboutActivity>
    private lateinit var actions: FileActions
    private lateinit var shelf: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("ui", 0).edit().clear().commit()
        FileWork.reset()
        ConvertRates.reset(context)
        shelf = File(context.filesDir, "convert").apply {
            deleteRecursively()
            mkdirs()
        }
        controller = Robolectric.buildActivity(AboutActivity::class.java).create()
        actions = FileActions(controller.get(), Library(context), Prefs(context), { "Device" }, {}, {})
        controller.start().resume()
    }

    @After
    fun tearDown() {
        controller.pause().stop().destroy()
        ConvertRates.reset(context)
        FileWork.reset()
    }

    /** A JPEG-named page of [size] bytes. */
    private fun page(size: Int) = ByteArray(size).also {
        it[0] = 0xFF.toByte()
        it[1] = 0xD8.toByte()
        it[2] = 0xFF.toByte()
    }

    private fun book(name: String, pages: Int): File {
        val file = File(shelf, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            repeat(pages) { i ->
                val data = page(4000)
                val e = ZipEntry("%02d.jpg".format(i)).apply {
                    method = ZipEntry.STORED
                    size = data.size.toLong()
                    crc = CRC32().apply { update(data) }.value
                }
                zip.putNextEntry(e)
                zip.write(data)
                zip.closeEntry()
            }
        }
        return file
    }

    /** Rates as if small JPEGs took [seconds] each to compact on this device. */
    private fun measured(seconds: Double) {
        File(context.noBackupFilesDir, "convert_rates.txt").writeText("page/compact/jpeg/s=300,${300 * seconds}\n")
        ConvertRates.reset()
    }

    /** "Takes about [minutes] min", and as long as the slowdown before any is measured says with the app away. */
    private fun takes(minutes: Int) = context.getString(R.string.takes_away, context.getString(R.string.takes_minutes, minutes), TimeText.span(context, minutes * 60 * WorkRates.BACK_SLOWDOWN))

    private fun estimateLine(): TextView {
        val line = (ShadowDialog.getLatestDialog() as AlertDialog).findViewById<TextView>(R.id.convert_estimate)!!
        idleUntil("the estimate") { line.text.toString() != context.getString(R.string.takes_estimating) }
        return line
    }

    @Test
    fun compactsDialogSaysHowLongItTakes() {
        measured(100.0)
        val path = book("Book A.cbz", 6).absolutePath
        actions.rebuild(listOf(path), FileJobs.Rebuild.COMPACT)
        val line = estimateLine()
        assertEquals(View.VISIBLE, line.visibility)
        // Six pages at about 100 s each.
        assertEquals(takes(10), line.text.toString())
    }

    @Test
    fun aQuickJobTakesUnderAMinute() {
        val file = book("Book B.cbz", 2)
        actions.rebuild(listOf(file.absolutePath), FileJobs.Rebuild.REPACK)
        assertEquals(context.getString(R.string.takes_under_minute), estimateLine().text.toString())
        // The same book read as slow as five minutes: the estimate is no constant.
        File(context.noBackupFilesDir, "convert_rates.txt").writeText("read/device/zip=1e12,${1e12 * 300.0 / file.length()}")
        ConvertRates.reset()
        actions.rebuild(listOf(file.absolutePath), FileJobs.Rebuild.REPACK)
        assertEquals(takes(5), estimateLine().text.toString())
    }

    @Test
    fun theEstimateLineHidesWhenNothingCanBeEstimated() {
        val dialog = AlertDialog.Builder(controller.get()).setView(R.layout.dialog_estimate).show()
        val line = dialog.findViewById<TextView>(R.id.convert_estimate)!!
        val failed = CompletableFuture<List<Double>>().apply { completeExceptionally(IOException("unreadable")) }
        TimeText.show(line, controller.get().lifecycleScope, failed)
        idleUntil("the estimate line hidden") { line.visibility == View.GONE }
        assertEquals(View.GONE, line.visibility)
    }

    @Test
    fun packsDialogSaysHowLongItTakesToo() {
        measured(30.0)
        val dir = File(shelf, "Loose").apply { mkdirs() }
        repeat(4) { File(dir, "%02d.jpg".format(it)).writeBytes(page(4000)) }
        actions.pack(listOf(dir), compacted = true)
        assertEquals(takes(2), estimateLine().text.toString())
        // Packed as they are, the pages are only copied.
        actions.pack(listOf(dir), compacted = false)
        assertEquals(context.getString(R.string.takes_under_minute), estimateLine().text.toString())
    }

    @Test
    fun timesReadAsMinutesThenHours() {
        assertEquals("Takes under a minute", TimeText.takes(context, 30.0))
        assertEquals("Takes about 12 min", TimeText.takes(context, 12 * 60.0))
        assertEquals("Takes about 2 h", TimeText.takes(context, 121 * 60.0))
        assertEquals("Takes about 3 h 20 min", TimeText.takes(context, 198 * 60.0))
        assertEquals("Under a minute left", TimeText.left(context, 59.0))
        assertEquals("About 4 min left", TimeText.left(context, 250.0))
        assertEquals("About 1 h 40 min left", TimeText.left(context, 100 * 60.0))
        assertEquals("Takes about 12 min, about 40 min with the app away", TimeText.takes(context, 12 * 60.0, 40 * 60.0))
        // Both read alike: said once.
        assertEquals("Takes under a minute", TimeText.takes(context, 10.0, 40.0))
    }

    @Test
    fun theNotificationTellsTheTimeLeftOfAJobWithAPlan() {
        FileWork.inFront = { true }
        val status = FileWork.Status(FileWork.Kind.COMPACT, "Book A.cbz", started = 1_000, done = 0, total = 0, plan = listOf(600.0))
        assertEquals("Book A.cbz · About 10 min left", FileWorkService.textOf(context, status, 1_000))
        // Halfway through on plan, five minutes later.
        val half = status.copy(done = 50, total = 100)
        assertEquals("Book A.cbz · About 5 min left", FileWorkService.textOf(context, half, 301_000))
        // Without a plan, or not yet running, only what it works on.
        assertEquals("Book A.cbz", FileWorkService.textOf(context, status.copy(plan = null), 1_000))
        assertEquals("Book A.cbz", FileWorkService.textOf(context, status.copy(started = null), 1_000))
        val many = status.copy(count = 3, index = 1, plan = listOf(60.0, 60.0, 60.0))
        assertTrue(FileWorkService.textOf(context, many, 61_000).endsWith("About 2 min left"))
        assertNotEquals(FileWorkService.textOf(context, many, 61_000), FileWorkService.nameOf(context, many))
    }

    @Test
    fun withTheAppAwayTheTimeLeftRunsAtItsPace() {
        FileWork.slowdown = { 4.0 }
        try {
            FileWork.inFront = { false }
            val status = FileWork.Status(FileWork.Kind.COMPACT, "Book A.cbz", started = 1_000, plan = listOf(600.0), since = FileWork.Since(1_000, 0, 0.0, front = true))
            assertEquals("Book A.cbz · About 40 min left", FileWorkService.textOf(context, status, 1_000))
            FileWork.inFront = { true }
            assertEquals("Book A.cbz · About 10 min left", FileWorkService.textOf(context, status, 1_000))
        } finally {
            FileWork.inFront = { true }
            FileWork.slowdown = { 1.0 }
        }
    }
}
