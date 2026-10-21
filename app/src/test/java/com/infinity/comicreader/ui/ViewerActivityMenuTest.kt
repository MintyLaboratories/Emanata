package com.infinity.comicreader.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ResumeMode
import com.infinity.comicreader.reader.Fixtures
import org.junit.Assert.assertEquals
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
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** The reader's toolbar and short overflow menu, and its Book info. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerActivityMenuTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Application
    private lateinit var dir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        Prefs(context).resumeMode = ResumeMode.NO
        dir = tmp.newFolder("shelf")
    }

    private val info = "<ComicInfo><Series>Series C</Series><Number>1</Number><Manga>YesAndRightToLeft</Manga></ComicInfo>"

    /** A zip named `.cbr`, with four pages and [comicInfo]. */
    private fun book(comicInfo: String? = info): File {
        val pages = (0 until 4).map { Fixtures.entry("p%02d.png".format(it), byteArrayOf(0)) }
        val extra = comicInfo?.let { listOf(Fixtures.entry("ComicInfo.xml", it.toByteArray())) }.orEmpty()
        return Fixtures.zip(File(dir, "Series C 001.cbr"), *(pages + extra).toTypedArray())
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun launch(book: File): ViewerActivity {
        val intent = Intent(context, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_PATH, book.absolutePath)
        val viewer = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup().get()
        val progress = viewer.findViewById<View>(R.id.viewer_progress)
        val deadline = System.currentTimeMillis() + 5_000
        do {
            Thread.sleep(10)
            idle()
        } while (progress.visibility == View.VISIBLE && System.currentTimeMillis() < deadline)
        viewer.invalidateOptionsMenu()
        idle()
        return viewer
    }

    private fun ViewerActivity.menu() = findViewById<Toolbar>(R.id.viewer_toolbar).menu

    /** Opens Book info and returns its text once the book's details are read. */
    private fun ViewerActivity.bookInfo(): String {
        val before = ShadowDialog.getLatestDialog()
        shadowOf(this).clickMenuItem(R.id.menu_book_info)
        val deadline = System.currentTimeMillis() + 5_000
        while (ShadowDialog.getLatestDialog() === before && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            idle()
        }
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        return dialog.findViewById<TextView>(android.R.id.message)!!.text.toString()
    }

    @Test
    fun theOverflowIsShort() {
        val menu = launch(book()).menu()
        val shown = (0 until menu.size()).map { menu.getItem(it) }.filter { it.isVisible }.map { it.itemId }
        assertEquals(
            listOf(
                R.id.menu_bookmark, R.id.menu_tune, R.id.menu_jump, R.id.menu_bookmark_list, R.id.menu_favorites,
                R.id.menu_auto_scroll, R.id.menu_page_tools, R.id.menu_book_info, R.id.menu_settings
            ),
            shown
        )
        val page = menu.findItem(R.id.menu_page_tools).subMenu!!
        assertEquals(
            listOf(R.id.menu_share_page, R.id.menu_save_page, R.id.menu_crop, R.id.menu_rotate),
            (0 until page.size()).map { page.getItem(it).itemId }
        )
    }

    @Test
    fun theBookmarkButtonSaysWhatATapDoes() {
        val viewer = launch(book())
        assertEquals(context.getString(R.string.menu_bookmark_add), viewer.menu().findItem(R.id.menu_bookmark).title.toString())
        shadowOf(viewer).clickMenuItem(R.id.menu_bookmark)
        viewer.invalidateOptionsMenu()
        idle()
        assertEquals(context.getString(R.string.menu_bookmark_remove), viewer.menu().findItem(R.id.menu_bookmark).title.toString())
    }

    @Test
    fun bookInfoShowsTheFileItsContentsAndTheDirection() {
        val book = book()
        val text = launch(book).bookInfo()
        assertTrue(text, text.contains(book.name))
        assertTrue(text, text.contains("CBR (ZIP)"))
        assertTrue(text, text.contains("\n4\n"))
        assertTrue(text, text.contains("Right-to-left (from ComicInfo)"))
        assertTrue(text, text.contains("Series: Series C\nNumber: 1\nManga: YesAndRightToLeft"))
    }

    @Test
    fun bookInfoSaysWhenTheFolderSetTheDirection() {
        val viewer = launch(book(comicInfo = null))
        assertTrue(viewer.bookInfo().contains("Left-to-right (default)"))
        ReaderSheet(viewer).tap(R.id.btn_dir_rtl).dialog.dismiss()
        idle()
        val text = viewer.bookInfo()
        assertTrue(text, text.contains("Right-to-left (set for this folder)"))
        assertTrue(text, !text.contains(context.getString(R.string.info_comic_info)))
    }

    @Test
    fun aLongPressOnAPageOffersItsActions() {
        val viewer = launch(book())
        val pager = viewer.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.viewer_pager)
        pager.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY))
        pager.layout(0, 0, 800, 1200)
        idle()
        val page = descendants(pager).filterIsInstance<ZoomImageView>().first()
        val down = android.os.SystemClock.uptimeMillis()
        page.onTouchEvent(android.view.MotionEvent.obtain(down, down, android.view.MotionEvent.ACTION_DOWN, 400f, 600f, 0))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1_000))
        page.onTouchEvent(android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), android.view.MotionEvent.ACTION_UP, 400f, 600f, 0))
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(context.getString(R.string.page_actions_title, 1), dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)!!.text.toString())
        assertEquals(4, dialog.listView.adapter.count)
        assertEquals(context.getString(R.string.menu_share_page), dialog.listView.adapter.getItem(0).toString())
    }

    private fun descendants(v: View): List<View> =
        listOf(v) + if (v is android.view.ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
}
