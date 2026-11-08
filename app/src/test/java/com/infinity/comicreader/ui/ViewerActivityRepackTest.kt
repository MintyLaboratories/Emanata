package com.infinity.comicreader.ui

import android.app.Application
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.OpenBooks
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ResumeMode
import com.infinity.comicreader.files.ConvertRates
import com.infinity.comicreader.files.FileWork
import com.infinity.comicreader.files.WorkRates
import com.infinity.comicreader.reader.Fixtures
import com.infinity.comicreader.reader.Fixtures.entry
import com.infinity.comicreader.testing.bookId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** A solid archive opened in the reader: the offer to repack it as CBZ, and what each answer does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerActivityRepackTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Application
    private lateinit var library: Library
    private lateinit var dir: File
    private val pages = Array(4) { entry("p%02d.png".format(it), byteArrayOf(it.toByte())) }
    private val controllers = ArrayList<ActivityController<ViewerActivity>>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        Prefs(context).resumeMode = ResumeMode.NO
        library = Library(context)
        dir = tmp.newFolder("shelf")
        FileWork.reset()
        ConvertRates.reset(context)
    }

    @After
    fun tearDown() {
        controllers.forEach { if (!it.get().isDestroyed) it.pause().stop().destroy() }
        ConvertRates.reset(context)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun solid(name: String = "one.cbr") = File(dir, name).apply { writeBytes(Fixtures.rar4Bytes(*pages, solid = true)) }

    /** Opens [book] and runs the main looper until it shows and its check for solid had time to end. */
    private fun launch(book: File): ViewerActivity {
        val intent = Intent(context, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_PATH, book.absolutePath)
        val controller = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup()
        controllers += controller
        val viewer = controller.get()
        val progress = viewer.findViewById<View>(R.id.viewer_progress)
        val deadline = System.currentTimeMillis() + 5_000
        do {
            Thread.sleep(10)
            idle()
        } while ((progress.visibility == View.VISIBLE || offer() == null) && System.currentTimeMillis() < deadline)
        return viewer
    }

    /** The offer to repack, while it shows. */
    private fun offer(): AlertDialog? = (ShadowDialog.getLatestDialog() as? AlertDialog)?.takeIf {
        it.isShowing && it.findViewById<TextView>(android.R.id.message)?.text?.toString() == context.getString(R.string.repack_note)
    }

    @Test
    fun aSolidArchiveIsOfferedRepackingAndNotNowIsKept() {
        val book = solid()
        launch(book)
        val dialog = offer() ?: throw AssertionError("no offer")
        val title = dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)!!.text.toString()
        assertEquals(context.getString(R.string.repack_title_one, book.name), title)
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        idle()
        assertTrue(library.repackDeclined(bookId(book)))
        // Not asked again for that book.
        ShadowDialog.reset()
        launch(book)
        assertEquals(null, offer())
    }

    @Test
    fun theNoteDismissedByBackOrATapOutsideCountsAsNotNow() {
        val book = solid()
        launch(book)
        val dialog = offer() ?: throw AssertionError("no offer")
        // Back and a tap outside both cancel the dialog.
        dialog.cancel()
        idle()
        assertTrue(library.repackDeclined(bookId(book)))
    }

    @Test
    fun theBookClosedWhileTheNoteShowsLeavesItUnanswered() {
        val book = solid()
        launch(book)
        val dialog = offer() ?: throw AssertionError("no offer")
        controllers.last().pause().stop().destroy()
        idle()
        assertFalse(dialog.isShowing)
        assertFalse(library.repackDeclined(bookId(book)))
        // Offered again on the next open.
        ShadowDialog.reset()
        launch(book)
        assertNotNull(offer())
    }

    @Test
    fun notOfferedWhenTheSettingIsOffOrTheArchiveIsNotSolid() {
        Prefs(context).suggestRepack = false
        launch(solid())
        assertEquals(null, offer())
        Prefs(context).suggestRepack = true
        ShadowDialog.reset()
        launch(File(dir, "plain.cbr").apply { writeBytes(Fixtures.rar4Bytes(*pages)) })
        assertEquals(null, offer())
    }

    @Test
    fun repackingLetsTheBookGoAndQueuesTheOperation() {
        val book = solid()
        val viewer = launch(book)
        val dialog = offer() ?: throw AssertionError("no offer")
        dialog.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.compact_in_place)!!.isChecked = true
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        idle()
        assertTrue(viewer.isFinishing)
        assertFalse(OpenBooks.isOpen(book.absolutePath))
        assertTrue(Prefs(context).compactInPlace)
        val cbz = File(dir, "one.cbz")
        repeat(500) {
            idle()
            if (FileWork.operations.value.isNotEmpty() && FileWork.operations.value.all { it.outcome != null }) return@repeat
            Thread.sleep(10)
        }
        val status = FileWork.operations.value.single()
        assertEquals(FileWork.Kind.REPACK, status.kind)
        assertTrue("${status.outcome}", status.outcome is FileWork.Outcome.Done)
        assertTrue(cbz.isFile)
        assertFalse(book.exists())
        assertNotEquals(0, cbz.length())
        assertFalse("not offered for the CBZ", library.repackDeclined(bookId(cbz)))
    }

    @Test
    fun theOfferSaysHowLongRepackingTakes() {
        val book = solid()
        // Reading this device's RARs as slow as three minutes for this book.
        val perByte = 180.0 / book.length()
        File(context.noBackupFilesDir, "convert_rates.txt").writeText("read/device/rar=1e12,${1e12 * perByte}")
        ConvertRates.reset()
        launch(book)
        val dialog = offer() ?: throw AssertionError("no offer")
        val line = dialog.findViewById<TextView>(R.id.convert_estimate)!!
        repeat(300) {
            idle()
            if (line.text.toString() != context.getString(R.string.takes_estimating)) return@repeat
            Thread.sleep(10)
        }
        assertEquals(View.VISIBLE, line.visibility)
        val away = context.getString(R.string.span_minutes, (3 * WorkRates.BACK_SLOWDOWN).toInt())
        assertEquals(context.getString(R.string.takes_away, context.getString(R.string.takes_minutes, 3), away), line.text.toString())
    }
}
