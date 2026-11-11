package com.infinity.comicreader.ui

import com.infinity.comicreader.testing.ref
import com.infinity.comicreader.testing.bookId
import android.app.Application
import android.app.Dialog
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
import com.infinity.comicreader.data.Bookmark
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ResumeMode
import com.infinity.comicreader.reader.Adjustments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The reader's Reset adjustments and Clear bookmarks ask first and act only when confirmed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerActivityConfirmTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Application
    private lateinit var library: Library
    private lateinit var book: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        Prefs(context).resumeMode = ResumeMode.NO
        library = Library(context)
        book = File(tmp.newFolder("attic"), "one.cbz")
        ZipOutputStream(book.outputStream()).use { zip ->
            repeat(5) {
                zip.putNextEntry(ZipEntry("p%02d.png".format(it)))
                zip.write(byteArrayOf(0))
                zip.closeEntry()
            }
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Opens the book and runs the main looper until the archive, opened off the main thread, is shown. */
    private fun launch(): ViewerActivity {
        val intent = Intent(context, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_PATH, book.absolutePath)
        val viewer = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup().get()
        val progress = viewer.findViewById<View>(R.id.viewer_progress)
        val deadline = System.currentTimeMillis() + 5_000
        do {
            Thread.sleep(10)
            idle()
        } while (progress.visibility == View.VISIBLE && System.currentTimeMillis() < deadline)
        return viewer
    }

    private fun ViewerActivity.latestDialog() = latestDialogOf(this) as AlertDialog

    private fun AlertDialog.message() = findViewById<TextView>(android.R.id.message)!!.text.toString()

    private fun AlertDialog.press(which: Int) {
        getButton(which).performClick()
        idle()
    }

    private fun ViewerActivity.menu(id: Int): AlertDialog {
        shadowOf(this).clickMenuItem(id)
        idle()
        return latestDialog()
    }

    /** Opens the reader settings sheet, taps its adjustments' Reset, and returns the sheet and the dialog showing afterward. */
    private fun ViewerActivity.tapResetAdjustments(): Pair<Dialog, Dialog> {
        val sheet = ReaderSheet(this).tap(R.id.adjust_reset)
        return sheet.dialog to latestDialogOf(this)
    }

    @Test
    fun resetAdjustmentsWaitsForConfirmation() {
        val own = Adjustments(10, -5, 20)
        library.setAdjustments(ref(book), own)
        val viewer = launch()

        val canceled = viewer.tapResetAdjustments().second as AlertDialog
        assertEquals(context.getString(R.string.confirm_reset_adjust_msg), canceled.message())
        canceled.press(DialogInterface.BUTTON_NEGATIVE)
        assertEquals(own, library.adjustmentsFor(bookId(book)))

        (viewer.tapResetAdjustments().second as AlertDialog).press(DialogInterface.BUTTON_POSITIVE)
        assertNull(library.adjustmentsFor(bookId(book)))
    }

    @Test
    fun resetAdjustmentsOnDefaultsDoesNotAsk() {
        val viewer = launch()
        val (panel, after) = viewer.tapResetAdjustments()
        assertSame(panel, after)
        assertNull(library.adjustmentsFor(bookId(book)))
    }

    @Test
    fun clearBookBookmarksWaitsForConfirmation() {
        for (page in listOf(1, 3)) library.addBookmark(Bookmark(book.absolutePath, book.name, page, "", 1L, bookId(book)))
        val viewer = launch()

        viewer.menu(R.id.menu_bookmark_list).press(DialogInterface.BUTTON_NEUTRAL)
        val canceled = viewer.latestDialog()
        assertEquals(context.getString(R.string.confirm_clear_book_bookmarks_msg, book.name), canceled.message())
        canceled.press(DialogInterface.BUTTON_NEGATIVE)
        assertEquals(2, library.bookmarksFor(bookId(book)).size)

        viewer.menu(R.id.menu_bookmark_list).press(DialogInterface.BUTTON_NEUTRAL)
        viewer.latestDialog().press(DialogInterface.BUTTON_POSITIVE)
        assertEquals(0, library.bookmarksFor(bookId(book)).size)
    }
}
