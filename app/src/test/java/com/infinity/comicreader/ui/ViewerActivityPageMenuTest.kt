package com.infinity.comicreader.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager2.widget.ViewPager2
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.PageAction
import com.infinity.comicreader.data.PageActions
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ResumeMode
import com.infinity.comicreader.data.SpreadDisplay
import com.infinity.comicreader.data.SpreadOverrides
import com.infinity.comicreader.data.TwoPage
import com.infinity.comicreader.reader.Fixtures
import com.infinity.comicreader.reader.TestImages
import com.infinity.comicreader.testing.bookId
import com.infinity.comicreader.testing.idleUntil
import com.infinity.comicreader.testing.ref
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

/** The page long-press menu: its fixed top row, the rows Settings orders, and what each does to the page pressed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerActivityPageMenuTest {

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

    /** Eight portrait pages, "Page 01.png" to "Page 08.png": two a screen as [1] [2|3] [4|5] [6|7] [8]. */
    private fun book(): File {
        val portrait = TestImages.png(26, 40)
        return Fixtures.zip(File(tmp.newFolder("shelf"), "Book 01.cbz"), *(1..8).map { Fixtures.entry("Page %02d.png".format(it), portrait) }.toTypedArray())
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun ViewerActivity.settle(): ViewerActivity {
        val progress = findViewById<View>(R.id.viewer_progress)
        idleUntil("the book open") { progress.visibility != View.VISIBLE }
        return this
    }

    private fun launch(book: File, page: Int = 0): ViewerActivity {
        val intent = Intent(context, ViewerActivity::class.java)
            .putExtra(ViewerActivity.EXTRA_PATH, book.absolutePath)
            .putExtra(ViewerActivity.EXTRA_PAGE, page)
        return Robolectric.buildActivity(ViewerActivity::class.java, intent).setup().get().settle()
    }

    /** The menu of [page], as a long-press on it opens it. */
    private fun ViewerActivity.menu(page: Int): AlertDialog {
        showPageActions(page)
        idle()
        return latestDialogOf(this) as AlertDialog
    }

    private fun AlertDialog.topLabels(): List<String> {
        val top = findViewById<ViewGroup>(R.id.page_actions_top)!!
        return (0 until top.childCount).map { top.getChildAt(it).findViewById<TextView>(R.id.bar_action_label).text.toString() }
    }

    private fun AlertDialog.rows(): List<View> {
        val rows = findViewById<ViewGroup>(R.id.page_actions_rows)!!
        return (0 until rows.childCount).map { rows.getChildAt(it) }
    }

    private fun AlertDialog.rowLabels(): List<String> = rows().map { it.findViewById<TextView>(R.id.more_action_label).text.toString() }

    private fun AlertDialog.row(action: PageAction): View = rows().single { it.tag == action }

    @Test
    fun theMenuHasItsFixedRowThenTheRowsSettingsOrder() {
        val book = book()
        val viewer = launch(book)
        val menu = viewer.menu(2)
        assertEquals(context.getString(R.string.page_actions_title, 3), menu.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)!!.text.toString())
        assertEquals(listOf("Share", "Save", "Crop", "Bookmark"), menu.topLabels())
        // One page a screen merges spreads in two files: a page alone may pair with the next.
        assertEquals(listOf("Hide this page", "Set as cover", "Page info", "Pair with next"), menu.rowLabels())
        menu.dismiss()
        Prefs(context).pageActions = PageActions.DEFAULT.with(PageAction.HIDE, false).moved(2, 0)
        assertEquals(listOf("Page info", "Set as cover", "Pair with next"), viewer.menu(2).rowLabels())
        // Shown one by one, pages pair with none.
        Prefs(context).spreads = SpreadDisplay.SPLIT
        val split = launch(book)
        assertEquals(listOf("Page info", "Set as cover"), split.menu(2).rowLabels())
    }

    @Test
    fun bookmarkAndCoverApplyToThePagePressed() {
        val book = book()
        val id = bookId(book)
        val viewer = launch(book)
        viewer.menu(5).findViewById<View>(R.id.page_action_bookmark)!!.performClick()
        idle()
        assertEquals(listOf(5), library.bookmarksFor(id).map { it.page })
        val menu = viewer.menu(5)
        assertEquals("Remove bookmark", menu.topLabels()[3])
        menu.findViewById<View>(R.id.page_action_bookmark)!!.performClick()
        idle()
        assertTrue(library.bookmarksFor(id).isEmpty())

        viewer.menu(4).row(PageAction.SET_COVER).performClick()
        idle()
        assertEquals(context.getString(R.string.page_cover_set), ShadowToast.getTextOfLatestToast())
        assertEquals(4, library.coverPage(id))
        val again = viewer.menu(4)
        assertTrue(again.rowLabels().contains(context.getString(R.string.page_reset_cover)))
        again.row(PageAction.SET_COVER).performClick()
        idle()
        assertNull(library.coverPage(id))
    }

    @Test
    fun aCoverIsKeptByBookPageWhilePagesAreHidden() {
        val book = book()
        library.setHiddenPages(ref(book), setOf(0, 1), 8)
        val viewer = launch(book)
        // Shown page 1 is the book's fourth.
        viewer.menu(1).row(PageAction.SET_COVER).performClick()
        idle()
        assertEquals(3, library.coverPage(bookId(book)))
    }

    @Test
    fun hideHidesThePagePressed() {
        val book = book()
        val viewer = launch(book, 1)
        viewer.menu(4).row(PageAction.HIDE).performClick()
        viewer.settle()
        assertEquals(setOf(4), library.hiddenPages(bookId(book)))
        // Unhiding is not in this menu: the toast names the overflow menu's entry.
        val unhide = context.getString(R.string.menu_unhide_pages)
        assertEquals(context.getString(R.string.page_hidden, unhide), ShadowToast.getTextOfLatestToast())
        assertTrue(ShadowToast.getTextOfLatestToast().contains("“$unhide” in the ⋮ menu"))
    }

    @Test
    fun pageInfoTellsNameSizeFormatAndBytes() {
        val viewer = launch(book())
        val before = latestDialogOrNullOf(viewer)
        viewer.menu(2).row(PageAction.PAGE_INFO).performClick()
        fun message() = (latestDialogOf(viewer) as AlertDialog).findViewById<TextView>(android.R.id.message)?.text?.toString().orEmpty()
        idleUntil("Page info's text") { latestDialogOf(viewer) !== before && message().isNotEmpty() }
        val text = (latestDialogOf(viewer) as AlertDialog).findViewById<TextView>(android.R.id.message)!!.text.toString()
        assertTrue(text, text.contains("Page 03.png"))
        assertTrue(text, text.contains("26 × 40"))
        assertTrue(text, text.contains("PNG, lossless"))
        assertTrue(text, text.contains(context.getString(R.string.page_info_bytes)))
    }

    @Test
    fun pageInfoLeavesTheSpinnerToAJobStillRunning() {
        val viewer = launch(book())
        val progress = viewer.findViewById<View>(R.id.viewer_progress)
        // A Share or Save still running.
        viewer.holdProgress()
        val menu = viewer.menu(2)
        menu.row(PageAction.PAGE_INFO).performClick()
        idleUntil("Page info shown") { latestDialogOf(viewer) !== menu }
        assertTrue(latestDialogOf(viewer) !== menu)
        assertEquals(View.VISIBLE, progress.visibility)
        viewer.releaseProgress()
        assertEquals(View.GONE, progress.visibility)
    }

    @Test
    fun twoPagesAScreenShowAPageAloneOrPairItAndUndoTheChoice() {
        Prefs(context).twoPage = TwoPage.ON
        val book = book()
        val id = bookId(book)
        val viewer = launch(book, 3)
        val pager = viewer.findViewById<ViewPager2>(R.id.viewer_pager)
        assertEquals(5, pager.adapter!!.itemCount)
        // The last page, alone, pairs with none.
        assertEquals("Page info", viewer.menu(7).rowLabels().last())
        // Page 4 shares a screen with page 5.
        val menu = viewer.menu(3)
        assertEquals(listOf("Hide this page", "Set as cover", "Page info", "Show alone"), menu.rowLabels())
        menu.row(PageAction.SPREAD).performClick()
        viewer.settle()
        assertEquals(SpreadOverrides(alone = setOf(3)), library.spreadOverrides(id))
        // [1] [2|3] [4] [5|6] [7|8]
        assertEquals(5, pager.adapter!!.itemCount)
        assertEquals("Show alone", viewer.menu(4).rowLabels().last())
        val alone = viewer.menu(3)
        assertEquals("Pair with next", alone.rowLabels().last())
        // Pairing it again only undoes the choice: that is how it paired.
        alone.row(PageAction.SPREAD).performClick()
        viewer.settle()
        assertTrue(library.spreadOverrides(id).isEmpty)

        // Page 3 paired with page 4 against the pairing names give: the run after starts anew.
        viewer.menu(2).row(PageAction.SPREAD).performClick()
        viewer.settle()
        assertEquals(SpreadOverrides(alone = setOf(2)), library.spreadOverrides(id))
        viewer.menu(2).row(PageAction.SPREAD).performClick()
        viewer.settle()
        assertEquals(SpreadOverrides(paired = setOf(2)), library.spreadOverrides(id))
        // [1] [2] [3|4] [5|6] [7|8]
        assertEquals(5, pager.adapter!!.itemCount)
        assertEquals("Show alone", viewer.menu(3).rowLabels().last())
        assertEquals("Show alone", viewer.menu(4).rowLabels().last())
    }
}
