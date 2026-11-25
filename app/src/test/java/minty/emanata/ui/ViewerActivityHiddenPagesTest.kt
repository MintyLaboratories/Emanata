package minty.emanata.ui

import minty.emanata.testing.idleUntil
import minty.emanata.testing.ref
import minty.emanata.testing.bookId
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager2.widget.ViewPager2
import minty.emanata.R
import minty.emanata.data.Bookmark
import minty.emanata.data.Library
import minty.emanata.data.Prefs
import minty.emanata.data.ResumeMode
import minty.emanata.data.TwoPage
import minty.emanata.reader.Fixtures
import minty.emanata.reader.TestImages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.File

/** "Hide this page": hidden pages leave the reader, and "Unhide pages…" shows them again. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerActivityHiddenPagesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Application
    private lateinit var library: Library

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        Prefs(context).resumeMode = ResumeMode.NO
        Prefs(context).twoPage = TwoPage.OFF
        library = Library(context)
    }

    private fun book(pages: Int): File =
        Fixtures.zip(File(tmp.newFolder("shelf"), "Book 01.cbz"), *(0 until pages).map { Fixtures.entry("p%02d.png".format(it), byteArrayOf(0)) }.toTypedArray())

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Waits for the book to open, or open again. */
    private fun ViewerActivity.settle(): ViewerActivity {
        val progress = findViewById<View>(R.id.viewer_progress)
        idleUntil("the book open") { progress.visibility != View.VISIBLE }
        return this
    }

    private fun launch(book: File, page: Int): ViewerActivity {
        val intent = Intent(context, ViewerActivity::class.java)
            .putExtra(ViewerActivity.EXTRA_PATH, book.absolutePath)
            .putExtra(ViewerActivity.EXTRA_PAGE, page)
        return Robolectric.buildActivity(ViewerActivity::class.java, intent).setup().get().settle()
    }

    private fun ViewerActivity.pager() = findViewById<ViewPager2>(R.id.viewer_pager)

    /** Opens Book info once the book's details are read. */
    private fun ViewerActivity.bookInfo(): AlertDialog {
        val before = latestDialogOrNullOf(this)
        shadowOf(this).clickMenuItem(R.id.menu_book_info)
        idleUntil("Book info shown") { latestDialogOrNullOf(this) !== before }
        return latestDialogOf(this) as AlertDialog
    }

    private fun AlertDialog.message() = findViewById<TextView>(android.R.id.message)!!.text.toString()

    /** The overflow's "Unhide pages…", as the menu shows it now. */
    private fun ViewerActivity.unhideItem(): MenuItem {
        invalidateOptionsMenu()
        idle()
        return findViewById<androidx.appcompat.widget.Toolbar>(R.id.viewer_toolbar).menu.findItem(R.id.menu_unhide_pages)
    }

    @Test
    fun aHiddenPageLeavesTheReaderAndComesBack() {
        val book = book(5)
        val id = bookId(book)
        library.addBookmark(Bookmark(book.absolutePath, book.name, 4, "", 1, id))
        val viewer = launch(book, 2)
        assertEquals(2, viewer.pager().currentItem)
        // Shown but disabled while nothing is hidden.
        assertTrue(viewer.unhideItem().isVisible)
        assertFalse(viewer.unhideItem().isEnabled)

        shadowOf(viewer).clickMenuItem(R.id.menu_hide_page)
        viewer.settle()
        // The toast names the overflow menu's entry that unhides, not Book info.
        assertTrue(ShadowToast.getTextOfLatestToast().contains("“${context.getString(R.string.menu_unhide_pages)}” in the ⋮ menu"))
        assertFalse(ShadowToast.getTextOfLatestToast().contains(context.getString(R.string.menu_book_info)))
        assertEquals(setOf(2), library.hiddenPages(id))
        assertEquals(4, viewer.pager().adapter!!.itemCount)
        // The next page shows where the hidden one was; the bookmark keeps its page.
        assertEquals(2, viewer.pager().currentItem)
        assertEquals(listOf(3), library.bookmarksFor(id).map { it.page })

        val info = viewer.bookInfo()
        assertTrue(info.message(), info.message().contains(context.getString(R.string.info_pages_hidden, 4, 1)))
        // Unhiding is the menu's own entry, no longer Book info's.
        assertEquals(View.GONE, info.getButton(AlertDialog.BUTTON_NEUTRAL).visibility)
        info.dismiss()
        assertTrue(viewer.unhideItem().isEnabled)
        shadowOf(viewer).clickMenuItem(R.id.menu_unhide_pages)
        idle()
        val unhide = latestDialogOf(viewer) as AlertDialog
        val grid = unhide.findViewById<RecyclerView>(R.id.hidden_pages_grid)!!
        grid.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.AT_MOST))
        grid.layout(0, 0, 800, grid.measuredHeight)
        idle()
        assertEquals(1, grid.adapter!!.itemCount)
        val cell = grid.findViewHolderForAdapterPosition(0)!!.itemView
        // Numbered as in the book.
        assertEquals("3", cell.findViewById<TextView>(R.id.thumb_label).text.toString())
        assertFalse(unhide.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        assertEquals(View.GONE, cell.findViewById<View>(R.id.thumb_check).visibility)
        cell.performClick()
        idle()
        grid.layout(0, 0, 800, grid.measuredHeight)
        idle()
        val marked = grid.findViewHolderForAdapterPosition(0)!!.itemView
        assertEquals(View.VISIBLE, marked.findViewById<View>(R.id.thumb_check).visibility)
        assertTrue(unhide.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        unhide.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        viewer.settle()
        assertTrue(library.hiddenPages(id).isEmpty())
        assertEquals(5, viewer.pager().adapter!!.itemCount)
        assertEquals(3, viewer.pager().currentItem)
        assertEquals(listOf(4), library.bookmarksFor(id).map { it.page })
    }

    @Test
    fun theMenuAsksWhichOfTwoPagesToHide() {
        Prefs(context).twoPage = TwoPage.ON
        val portrait = TestImages.png(26, 40)
        val book = Fixtures.zip(
            File(tmp.newFolder("pairs"), "Book 02.cbz"),
            *(1..8).map { Fixtures.entry("Page %02d.png".format(it), portrait) }.toTypedArray()
        )
        val viewer = launch(book, 1)
        // [1] [2|3] [4|5] [6|7] [8]: the second screen shows two pages.
        assertEquals(1, viewer.pager().currentItem)
        shadowOf(viewer).clickMenuItem(R.id.menu_hide_page)
        idle()
        val ask = latestDialogOf(viewer) as AlertDialog
        val list = ask.listView
        assertEquals(listOf("Page 2", "Page 3"), (0 until list.adapter.count).map { list.adapter.getItem(it).toString() })
        assertTrue(library.hiddenPages(bookId(book)).isEmpty())
        list.performItemClick(list.adapter.getView(1, null, list), 1, 1)
        viewer.settle()
        assertEquals(setOf(2), library.hiddenPages(bookId(book)))
    }

    @Test
    fun theLastPageShownStays() {
        val book = book(2)
        library.setHiddenPages(ref(book), setOf(0), 2)
        val viewer = launch(book, 0)
        assertEquals(1, viewer.pager().adapter!!.itemCount)
        shadowOf(viewer).clickMenuItem(R.id.menu_hide_page)
        idle()
        assertEquals(context.getString(R.string.hide_last_page), ShadowToast.getTextOfLatestToast())
        assertEquals(setOf(0), library.hiddenPages(bookId(book)))
    }

    @Test
    fun hiddenPagesStayOutOfHistoryCounts() {
        val book = book(6)
        library.setHiddenPages(ref(book), setOf(1, 5), 6)
        val intent = Intent(context, ViewerActivity::class.java)
            .putExtra(ViewerActivity.EXTRA_PATH, book.absolutePath)
            .putExtra(ViewerActivity.EXTRA_PAGE, 3)
        val controller = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup()
        controller.get().settle()
        controller.pause().stop()
        val item = library.historyFor(bookId(book))!!
        assertEquals(4, item.total)
        assertEquals(3, item.page)
    }
}
