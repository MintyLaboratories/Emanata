package com.infinity.comicreader.ui

import com.infinity.comicreader.testing.idleUntil
import com.infinity.comicreader.testing.ref
import com.infinity.comicreader.testing.bookId
import android.app.Application
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.CheckBox
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.data.HistoryItem
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ResumeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Where a book opens under each "Resume where I left off" choice, and the Ask dialog. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerActivityResumeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Application
    private lateinit var prefs: Prefs
    private lateinit var library: Library
    private lateinit var book: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = Prefs(context)
        library = Library(context)
        book = File(tmp.newFolder("attic"), "one.cbz")
        ZipOutputStream(book.outputStream()).use { zip ->
            repeat(8) {
                zip.putNextEntry(ZipEntry("p%02d.png".format(it)))
                zip.write(byteArrayOf(0))
                zip.closeEntry()
            }
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun savePosition(page: Int) =
        library.addHistory(HistoryItem(book.absolutePath, book.name, page, 8, 1L, bookId(book)))

    /** Opens the book without a start page and waits until it is shown. */
    private fun launch(): ViewerActivity {
        val intent = Intent(context, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_PATH, book.absolutePath)
        val viewer = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup().get()
        val progress = viewer.findViewById<View>(R.id.viewer_progress)
        idleUntil("the book open") { progress.visibility != View.VISIBLE }
        return viewer
    }

    private fun ViewerActivity.page(): Int = findViewById<SeekBar>(R.id.viewer_seek).progress

    private fun askDialog(): AlertDialog? = ShadowDialog.getLatestDialog() as? AlertDialog

    @Test
    fun noSavedPositionNeverAsks() {
        val viewer = launch()
        assertNull(askDialog())
        assertEquals(0, viewer.page())
    }

    @Test
    fun finishedBookOpensAtTheFirstPage() {
        savePosition(5)
        library.setRead(ref(book), true)
        val viewer = launch()
        assertNull(askDialog())
        assertEquals(0, viewer.page())
    }

    @Test
    fun yesAndNoDoNotAsk() {
        savePosition(5)
        prefs.resumeMode = ResumeMode.YES
        assertEquals(5, launch().page())
        assertNull(askDialog())

        prefs.resumeMode = ResumeMode.NO
        assertEquals(0, launch().page())
        assertNull(askDialog())
    }

    @Test
    fun askOffersBothWithDontAskAgainOff() {
        savePosition(5)
        val viewer = launch()
        val dialog = askDialog()
        assertNotNull(dialog)
        dialog!!
        val box = dialog.findViewById<CheckBox>(R.id.resume_dont_ask)!!
        assertFalse(box.isChecked)
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        idle()
        assertEquals(0, viewer.page())
        assertEquals(ResumeMode.ASK, prefs.resumeMode)
    }

    @Test
    fun dontAskAgainWithResumeMeansYes() {
        savePosition(5)
        val viewer = launch()
        val dialog = askDialog()!!
        dialog.findViewById<CheckBox>(R.id.resume_dont_ask)!!.isChecked = true
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(5, viewer.page())
        assertEquals(ResumeMode.YES, prefs.resumeMode)
    }

    @Test
    fun dontAskAgainWithFirstPageMeansNo() {
        savePosition(5)
        val viewer = launch()
        val dialog = askDialog()!!
        dialog.findViewById<CheckBox>(R.id.resume_dont_ask)!!.isChecked = true
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        idle()
        assertEquals(0, viewer.page())
        assertEquals(ResumeMode.NO, prefs.resumeMode)
    }
}
