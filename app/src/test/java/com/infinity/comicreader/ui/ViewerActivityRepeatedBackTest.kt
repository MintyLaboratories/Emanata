package com.infinity.comicreader.ui

import com.infinity.comicreader.testing.bookId
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager2.widget.ViewPager2
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.TwoPage
import com.infinity.comicreader.reader.TestImages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.io.File
import java.time.Duration
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** "Previous chapter on repeated back": only back actions on the first page count, in time. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerActivityRepeatedBackTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Application
    private lateinit var prefs: Prefs
    private lateinit var first: File
    private lateinit var second: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = Prefs(context)
        val dir = tmp.newFolder("books")
        first = book(dir, "a.cbz", pages = 6)
        second = book(dir, "b.cbz", pages = 5)
    }

    private fun book(dir: File, name: String, pages: Int, image: ByteArray = byteArrayOf(0)): File {
        val f = File(dir, name)
        ZipOutputStream(f.outputStream()).use { zip ->
            repeat(pages) {
                zip.putNextEntry(ZipEntry("p%02d.png".format(it)))
                zip.write(image)
                zip.closeEntry()
            }
        }
        return f
    }

    /** A chapter after [first] whose pages are far taller than the screen in a strip. */
    private fun tallSecond(): File {
        second.delete()
        return book(first.parentFile!!, "b.cbz", pages = 3, image = TestImages.png(10, 80))
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun launch(file: File, page: Int): ViewerActivity {
        val intent = Intent(context, ViewerActivity::class.java)
            .putExtra(ViewerActivity.EXTRA_PATH, file.absolutePath)
            .putExtra(ViewerActivity.EXTRA_PAGE, page)
        val viewer = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup().get()
        val progress = viewer.findViewById<View>(R.id.viewer_progress)
        val deadline = System.currentTimeMillis() + 5_000
        do {
            Thread.sleep(10)
            idle()
        } while (progress.visibility == View.VISIBLE && System.currentTimeMillis() < deadline)
        return viewer
    }

    private fun ViewerActivity.back() {
        findViewById<View>(R.id.btn_prev_page).performClick()
        idle()
    }

    /** The page list touches go to: the pager's own list, or the strip. */
    private fun ViewerActivity.list(): RecyclerView =
        if (prefs.pageMode == "paged") findViewById<ViewPager2>(R.id.viewer_pager).getChildAt(0) as RecyclerView
        else findViewById(R.id.viewer_vertical)

    /** A one-finger drag across the page list from ([x0], [y0]) to ([x1], [y1]), then lifted. */
    private fun ViewerActivity.drag(x0: Float, y0: Float, x1: Float, y1: Float) {
        val rv = list()
        val t = SystemClock.uptimeMillis()
        rv.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x0, y0, 0))
        val steps = 10
        for (i in 1..steps) {
            val f = i / steps.toFloat()
            rv.dispatchTouchEvent(
                MotionEvent.obtain(t, t + 16L * i, MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * f, y0 + (y1 - y0) * f, 0)
            )
        }
        rv.dispatchTouchEvent(MotionEvent.obtain(t, t + 16L * (steps + 1), MotionEvent.ACTION_UP, x1, y1, 0))
        idle()
    }

    /** A swipe across most of the page's width: rightward, or leftward when [leftward]. */
    private fun ViewerActivity.swipe(leftward: Boolean = false) {
        val rv = list()
        val y = rv.height / 2f
        val (from, to) = rv.width * 0.1f to rv.width * 0.9f
        if (leftward) drag(to, y, from, y) else drag(from, y, to, y)
    }

    private fun ViewerActivity.page(): Int = findViewById<SeekBar>(R.id.viewer_seek).progress

    private fun ViewerActivity.hintShown() = findViewById<View>(R.id.viewer_hint).visibility == View.VISIBLE

    private fun ViewerActivity.opened(): Intent? = shadowOf(this).nextStartedActivity

    private fun assertOpensFirstAtItsEnd(intent: Intent?) {
        assertEquals(first.absolutePath, intent?.getStringExtra(ViewerActivity.EXTRA_PATH))
        assertEquals(Int.MAX_VALUE, intent?.getIntExtra(ViewerActivity.EXTRA_PAGE, -1))
    }

    @Test
    fun twoBacksOnTheFirstPageOpenThePreviousChapter() {
        val viewer = launch(second, 0)
        viewer.back()
        assertEquals(true, viewer.hintShown())
        assertNull(viewer.opened())
        viewer.back()
        assertOpensFirstAtItsEnd(viewer.opened())
    }

    @Test
    fun theBackThatReachesTheFirstPageDoesNotCount() {
        val viewer = launch(second, 1)
        viewer.back()
        assertEquals(0, viewer.page())
        assertEquals(false, viewer.hintShown())
        viewer.back()
        assertNull(viewer.opened())
        viewer.back()
        assertOpensFirstAtItsEnd(viewer.opened())
    }

    @Test
    fun inAStripTheBackThatScrollsTheFirstPageToItsTopDoesNotCount() {
        prefs.pageMode = "continuous"
        prefs.readingDir = "ttb"
        val viewer = launch(tallSecond(), 0)
        val strip = viewer.findViewById<RecyclerView>(R.id.viewer_vertical)
        strip.scrollBy(0, 100)
        idle()
        assertTrue(strip.canScrollVertically(-1))
        viewer.back()
        assertFalse(strip.canScrollVertically(-1))
        assertEquals(false, viewer.hintShown())
        viewer.back()
        assertEquals(true, viewer.hintShown())
        assertNull(viewer.opened())
        viewer.back()
        assertOpensFirstAtItsEnd(viewer.opened())
    }

    @Test
    fun twoSwipesBackOnTheFirstPageOpenThePreviousChapter() {
        val viewer = launch(second, 0)
        viewer.swipe()
        assertEquals(true, viewer.hintShown())
        assertNull(viewer.opened())
        viewer.swipe()
        assertOpensFirstAtItsEnd(viewer.opened())
    }

    @Test
    fun swipingForwardOnTheFirstPageDoesNotCount() {
        prefs.autoNext = false
        val viewer = launch(second, 0)
        viewer.swipe(leftward = true)
        assertEquals(false, viewer.hintShown())
    }

    @Test
    fun rightToLeftSwipesBackLeftward() {
        prefs.readingDir = "rtl"
        val viewer = launch(second, 0)
        viewer.swipe(leftward = true)
        assertEquals(true, viewer.hintShown())
        viewer.swipe(leftward = true)
        assertOpensFirstAtItsEnd(viewer.opened())
    }

    @Test
    fun aStripSwipesBackWithAFlickDown() {
        prefs.pageMode = "continuous"
        prefs.readingDir = "ttb"
        val viewer = launch(second, 0)
        val rv = viewer.list()
        val x = rv.width / 2f
        viewer.drag(x, rv.height * 0.2f, x, rv.height * 0.8f)
        assertEquals(true, viewer.hintShown())
        viewer.drag(x, rv.height * 0.2f, x, rv.height * 0.8f)
        assertOpensFirstAtItsEnd(viewer.opened())
    }

    @Test
    fun panningAZoomedFirstPagePastItsEdgeIsNoSwipeBack() {
        val viewer = launch(second, 0)
        val rv = viewer.list()
        val page = descendants(rv.getChildAt(0)).filterIsInstance<ZoomImageView>().first()
        page.setImageBitmap(Bitmap.createBitmap(rv.width, rv.height, Bitmap.Config.ARGB_8888))
        page.zoomTo(1.5f, rv.width / 2f, rv.height / 2f)
        // Pans to the page's left edge, then runs on past it.
        viewer.swipe()
        assertEquals(true, page.panned)
        assertEquals(false, viewer.hintShown())
    }

    private fun descendants(v: View): List<View> =
        listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { descendants(g.getChildAt(it)) } } ?: emptyList())

    @Test
    fun leavingTheFirstPageHidesTheHint() {
        val viewer = launch(second, 0)
        viewer.back()
        assertEquals(true, viewer.hintShown())
        viewer.findViewById<View>(R.id.btn_next_page).performClick()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        assertEquals(1, viewer.page())
        assertEquals(false, viewer.hintShown())
    }

    @Test
    fun theHintSitsAboveTheBottomBarOrTheScreensBottom() {
        val viewer = launch(second, 0)
        viewer.back()
        val hint = viewer.findViewById<View>(R.id.viewer_hint)
        val bar = viewer.findViewById<View>(R.id.viewer_bottom)
        val gap = viewer.resources.getDimensionPixelSize(R.dimen.reader_hint_gap).toFloat()
        assertTrue(bar.top > 0)
        assertEquals(bar.top - gap, hint.bottom + hint.translationY, 0.5f)
        bar.visibility = View.GONE
        idle()
        assertEquals(viewer.findViewById<View>(R.id.viewer_root).height - gap, hint.bottom + hint.translationY, 0.5f)
    }

    @Test
    fun aLateBackStartsOver() {
        val viewer = launch(second, 0)
        viewer.back()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1600))
        assertEquals(false, viewer.hintShown())
        viewer.back()
        assertNull(viewer.opened())
        viewer.back()
        assertOpensFirstAtItsEnd(viewer.opened())
    }

    @Test
    fun threeSwipesNeedThree() {
        prefs.prevChapterBacks = 3
        val viewer = launch(second, 0)
        viewer.back()
        viewer.back()
        assertNull(viewer.opened())
        viewer.back()
        assertOpensFirstAtItsEnd(viewer.opened())
    }

    @Test
    fun disabledStaysOnTheFirstPage() {
        prefs.prevChapterBacks = 0
        val viewer = launch(second, 0)
        repeat(4) { viewer.back() }
        assertNull(viewer.opened())
        assertEquals(false, viewer.hintShown())
        assertEquals(0, viewer.page())
    }

    @Test
    fun theFirstChapterHasNoPreviousToHintAt() {
        val viewer = launch(first, 0)
        repeat(2) { viewer.back() }
        assertNull(viewer.opened())
        assertEquals(false, viewer.hintShown())
    }

    @Test
    fun thePreviousChapterOpensAtItsLastPage() {
        assertEquals(5, launch(first, Int.MAX_VALUE).page())
    }

    @Test
    fun comingBackAtTheLastPageHoldsTheEndCardUntilMovingOn() {
        prefs.autoNext = false
        val viewer = launch(first, Int.MAX_VALUE)
        val card = viewer.findViewById<View>(R.id.viewer_end)
        assertEquals(View.GONE, card.visibility)
        viewer.findViewById<View>(R.id.btn_next_page).performClick()
        idle()
        assertEquals(View.VISIBLE, card.visibility)
    }

    @Test
    fun landingAtThePreviousChaptersEndDoesNotMarkItRead() {
        val library = Library(context)
        val viewer = launch(first, Int.MAX_VALUE)
        assertFalse(library.isRead(bookId(first)))
        viewer.back()
        assertFalse(library.isRead(bookId(first)))
        viewer.findViewById<View>(R.id.btn_next_page).performClick()
        idle()
        assertTrue(library.isRead(bookId(first)))
    }

    @Test
    fun inTwoPageModeAtItsLastSpread() {
        prefs.twoPage = TwoPage.ON
        prefs.coverAlone = false
        // Six pages make three spreads.
        assertEquals(2, launch(first, Int.MAX_VALUE).page())
    }
}
