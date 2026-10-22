package com.infinity.comicreader.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager2.widget.ViewPager2
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ResumeMode
import com.infinity.comicreader.data.TwoPage
import com.infinity.comicreader.reader.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.Duration

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
            listOf(R.id.menu_share_page, R.id.menu_save_page, R.id.menu_crop, R.id.menu_hide_page, R.id.menu_rotate),
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
    fun theBookmarkButtonIsARibbonFilledOnABookmarkedPage() {
        val viewer = launch(book())
        val ribbon = { shadowOf(viewer.menu().findItem(R.id.menu_bookmark).icon).createdFromResId }
        assertEquals(R.drawable.ic_bookmark_border, ribbon())
        shadowOf(viewer).clickMenuItem(R.id.menu_bookmark)
        viewer.invalidateOptionsMenu()
        idle()
        assertEquals(R.drawable.ic_bookmark, ribbon())
    }

    @Test
    fun bookInfoShowsTheFileItsContentsAndTheDirection() {
        val book = book()
        val text = launch(book).bookInfo()
        assertTrue(text, text.contains(book.name))
        assertTrue(text, text.contains("CBR (actually ZIP)"))
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
    fun bookInfoReadsAComicInfoStoredAfterTheSolidPages() {
        val pages = (0 until 4).map { Fixtures.entry("p%02d.png".format(it), byteArrayOf(0)) }
        val book = Fixtures.sevenZip(File(dir, "Series C 001.cb7"), *(pages + Fixtures.entry("ComicInfo.xml", info.toByteArray())).toTypedArray())
        val text = launch(book).bookInfo()
        assertTrue(text, text.contains("Series: Series C\nNumber: 1"))
    }

    @Test
    fun bookInfoOfALoneImage() {
        val image = File(dir, "Page 01.png").apply { writeBytes(byteArrayOf(0)) }
        val text = launch(image).bookInfo()
        assertTrue(text, text.contains("PNG"))
        assertTrue(text, text.contains("\n1\n"))
        assertTrue(text, !text.contains(context.getString(R.string.info_comic_info)))
    }

    @Test
    fun aSecondTapWhileBookInfoReadsIsIgnored() {
        val viewer = launch(book())
        val before = ShadowDialog.getShownDialogs().size
        // Two taps, then a third inside bookInfo(), all before the first read is done.
        shadowOf(viewer).clickMenuItem(R.id.menu_book_info)
        shadowOf(viewer).clickMenuItem(R.id.menu_book_info)
        viewer.bookInfo()
        idle()
        assertEquals(before + 1, ShadowDialog.getShownDialogs().size)
    }

    /** The pager laid out at [width] × [height]. */
    private fun ViewerActivity.pager(width: Int = 800, height: Int = 1200): ViewPager2 {
        val pager = findViewById<ViewPager2>(R.id.viewer_pager)
        pager.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        pager.layout(0, 0, width, height)
        idle()
        return pager
    }

    /** The page images of the pager's current item, left to right. */
    private fun ViewPager2.shownImages(): List<ZoomImageView> {
        val item = (getChildAt(0) as RecyclerView).findViewHolderForAdapterPosition(currentItem)!!.itemView
        return descendants(item).filterIsInstance<ZoomImageView>().sortedBy { image ->
            IntArray(2).also { image.getLocationInWindow(it) }[0]
        }
    }

    /** Holds [pointers] fingers on [view] for a second, then lifts them. */
    private fun press(view: View, pointers: Int = 1) {
        val down = SystemClock.uptimeMillis()
        val x = view.width / 2f
        val y = view.height / 2f
        view.onTouchEvent(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
        if (pointers > 1) view.onTouchEvent(twoFingers(down, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), x, y))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000))
        if (pointers > 1) view.onTouchEvent(twoFingers(down, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), x, y))
        view.onTouchEvent(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0))
    }

    private fun twoFingers(down: Long, action: Int, x: Float, y: Float): MotionEvent {
        val ids = Array(2) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(2) { MotionEvent.PointerCoords().apply { this.x = x + it * 200f; this.y = y } }
        return MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 2, ids, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
    }

    private fun AlertDialog.title() = findViewById<TextView>(androidx.appcompat.R.id.alertTitle)!!.text.toString()

    @Test
    fun aLongPressOnAPageOffersItsActions() {
        val page = launch(book()).pager().shownImages().first()
        press(page)
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(context.getString(R.string.page_actions_title, 1), dialog.title())
        assertEquals(5, dialog.listView.adapter.count)
        assertEquals(context.getString(R.string.menu_share_page), dialog.listView.adapter.getItem(0).toString())
    }

    @Test
    fun aLongPressOnASpreadNamesThePagePressed() {
        Prefs(context).twoPage = TwoPage.ON
        Prefs(context).coverAlone = false
        // Left to right, then a right-to-left book (from its ComicInfo), whose first page is on the right.
        for ((comicInfo, leftPage) in listOf(null to 1, info to 2)) {
            val (left, right) = launch(book(comicInfo)).pager(1600, 1000).shownImages()
            press(left)
            assertEquals(context.getString(R.string.page_actions_title, leftPage), (ShadowDialog.getLatestDialog() as AlertDialog).title())
            ShadowDialog.getLatestDialog().dismiss()
            press(right)
            assertEquals(context.getString(R.string.page_actions_title, 3 - leftPage), (ShadowDialog.getLatestDialog() as AlertDialog).title())
            ShadowDialog.getLatestDialog().dismiss()
        }
    }

    @Test
    fun aTwoFingerPressOffersNothing() {
        val page = launch(book()).pager().shownImages().first()
        val before = ShadowDialog.getLatestDialog()
        press(page, pointers = 2)
        assertSame(before, ShadowDialog.getLatestDialog())
    }

    @Test
    fun landscapeOnlyPairsPagesAfterATurnAndPartsThemAfterATurnBack() {
        Prefs(context).twoPage = TwoPage.LANDSCAPE
        Prefs(context).coverAlone = false
        val viewer = launch(book())
        val pager = viewer.pager()
        assertEquals(4, pager.adapter!!.itemCount)
        RuntimeEnvironment.setQualifiers("land")
        viewer.onConfigurationChanged(viewer.resources.configuration)
        idle()
        assertEquals(2, viewer.pager(1600, 1000).adapter!!.itemCount)
        RuntimeEnvironment.setQualifiers("port")
        viewer.onConfigurationChanged(viewer.resources.configuration)
        idle()
        assertEquals(4, viewer.pager().adapter!!.itemCount)
    }

    private fun descendants(v: View): List<View> =
        listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
}
