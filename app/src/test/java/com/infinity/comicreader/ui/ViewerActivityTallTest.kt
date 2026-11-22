package com.infinity.comicreader.ui

import android.app.Application
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Matrix
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager2.widget.ViewPager2
import com.infinity.comicreader.R
import com.infinity.comicreader.data.FolderLayout
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ResumeMode
import com.infinity.comicreader.reader.Fixtures
import com.infinity.comicreader.reader.TestImages
import com.infinity.comicreader.testing.bookId
import com.infinity.comicreader.testing.idleUntil
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.Duration

/**
 * Books of mostly tall pages are asked once whether to read as a continuous strip; a few
 * tall pages in a book show at fit width and scroll before the page turns.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViewerActivityTallTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var prefs: Prefs
    private lateinit var library: Library
    private lateinit var dir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("library", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        prefs = Prefs(context)
        prefs.resumeMode = ResumeMode.NO
        // Pages of one width and varying heights could read as slices of one strip.
        prefs.detectDirection = false
        library = Library(context)
        dir = tmp.newFolder("shelf")
        ShadowDialog.reset()
    }

    /** A book of [heights] pages 100 px wide. */
    private fun book(name: String, heights: List<Int>): File =
        Fixtures.zip(File(dir, name), *heights.mapIndexed { i, h -> Fixtures.entry("p%02d.png".format(i), TestImages.png(100, h)) }.toTypedArray())

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun launch(book: File, page: Int = 0): ViewerActivity {
        val intent = Intent(ApplicationProvider.getApplicationContext(), ViewerActivity::class.java)
            .putExtra(ViewerActivity.EXTRA_PATH, book.absolutePath)
        if (page > 0) intent.putExtra(ViewerActivity.EXTRA_PAGE, page)
        val viewer = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup().get()
        val progress = viewer.findViewById<View>(R.id.viewer_progress)
        idleUntil("the book open") { progress.visibility != View.VISIBLE }
        return viewer
    }

    /** Waits past the scans' two-second hold until all the open started is done: a question coming at all has come. */
    private fun ViewerActivity.settle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        idleUntil("all the open started done") { openSettled() }
    }

    private fun asked(): AlertDialog? = (ShadowDialog.getLatestDialog() as? AlertDialog)?.takeIf { it.isShowing }

    private fun AlertDialog.message() = findViewById<TextView>(android.R.id.message)!!.text.toString()

    private fun ViewerActivity.continuous() = findViewById<View>(R.id.viewer_vertical).visibility == View.VISIBLE

    @Test
    fun aBookOfTallPagesAsksOnceAndYesReadsItAsAStrip() {
        val tall = book("Tall 01.cbz", listOf(400, 900, 600, 140, 700))
        val viewer = launch(tall)
        idleUntil("the question") { asked() != null }
        val dialog = asked()!!
        assertEquals("Tall pages: read as a continuous strip?", dialog.message())
        assertFalse(viewer.continuous())
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        idle()
        assertTrue(viewer.continuous())
        assertEquals(true, library.tallStrip(bookId(tall)))
        // The defaults stay as they were.
        assertEquals("paged", prefs.pageMode)
        assertEquals("ltr", prefs.readingDir)
        viewer.finish()
        idle()
        ShadowDialog.reset()
        val again = launch(tall)
        assertTrue(again.continuous())
        idle()
        assertNull(asked())
    }

    @Test
    fun noIsKeptAndNeverAskedAgain() {
        val tall = book("Tall 02.cbz", listOf(400, 900, 600))
        val viewer = launch(tall)
        idleUntil("the question") { asked() != null }
        asked()!!.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        idle()
        assertFalse(viewer.continuous())
        assertEquals(false, library.tallStrip(bookId(tall)))
        viewer.finish()
        idle()
        ShadowDialog.reset()
        val again = launch(tall)
        again.settle()
        assertNull(asked())
        assertFalse(again.continuous())
    }

    @Test
    fun aFolderWithItsOwnDirectionIsNeverAsked() {
        library.setFolderLayout(dir.absolutePath, FolderLayout.Direction("ltr"))
        launch(book("Tall 03.cbz", listOf(400, 900, 600))).settle()
        assertNull(asked())
    }

    @Test
    fun aBookOfFewTallPagesIsNotAsked() {
        launch(book("Mixed 01.cbz", listOf(140, 140, 900, 140, 140))).settle()
        assertNull(asked())
    }

    @Test
    fun looseImagesAreJudgedByTheirFolderAndAnsweredForIt() {
        val images = (1..5).map { i -> File(dir, "img$i.png").apply { writeBytes(TestImages.png(100, 500)) } }
        val viewer = launch(images[2])
        idleUntil("the question") { asked() != null }
        asked()!!.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        idle()
        assertTrue(viewer.continuous())
        assertEquals(true, library.folderTallStrip(dir.absolutePath))
    }

    /** The page view of the pager's current item. */
    private fun ViewerActivity.shownImage(): ZoomImageView {
        val pager = findViewById<ViewPager2>(R.id.viewer_pager)
        val list = pager.getChildAt(0) as RecyclerView
        val holder = list.findViewHolderForAdapterPosition(pager.currentItem)!!
        return (holder as PageAdapter.PageHolder).images[0]
    }

    private fun ViewerActivity.next() {
        onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
    }

    @Test
    fun aTallPageInANormalBookScrollsAtFitWidthBeforeThePageTurns() {
        prefs.volumeKeys = true
        val viewer = launch(book("Mixed 02.cbz", listOf(140, 1000, 140)))
        val pager = viewer.findViewById<ViewPager2>(R.id.viewer_pager)
        idleUntil("no question showing") { asked() == null }
        viewer.next()
        assertEquals(1, pager.currentItem)
        idleUntil("the page shown") { viewer.shownImage().drawable != null }
        val image = viewer.shownImage()
        assertEquals(ZoomImageView.FitMode.WIDTH, image.fitMode)
        fun top() = FloatArray(9).also { image.imageMatrix.getValues(it) }[Matrix.MTRANS_Y]
        assertEquals(0f, top(), 0.5f)
        var presses = 0
        while (pager.currentItem == 1 && presses < 50) {
            viewer.next()
            presses++
        }
        assertEquals(2, pager.currentItem)
        // Scrolled most of a screen at a time to its bottom before turning.
        assertTrue("$presses presses", presses > 3)
    }

    /** Swipes [pager] a page back (toward the next one's side, [sign] 1) or on (-1), as a finger dragging most of the screen. */
    private fun swipe(pager: ViewPager2, sign: Int) {
        pager.beginFakeDrag()
        for (i in 1..10) pager.fakeDragBy(sign * pager.width * 0.08f)
        pager.endFakeDrag()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
    }

    /** Where the shown page's top is, and how far down it may go: its top at 0, its bottom at the second. */
    private fun ViewerActivity.shownTop(): Pair<Float, Float> {
        val image = shownImage()
        val values = FloatArray(9).also { image.imageMatrix.getValues(it) }
        val shownHeight = image.drawable.intrinsicHeight * values[Matrix.MSCALE_Y]
        assertTrue("taller than the screen", shownHeight > image.height)
        return values[Matrix.MTRANS_Y] to image.height - shownHeight
    }

    @Test
    fun swipingBackOntoATallPageLandsAtItsBottom() {
        val viewer = launch(book("Mixed 03.cbz", listOf(140, 1000, 140)), page = 2)
        val pager = viewer.findViewById<ViewPager2>(R.id.viewer_pager)
        idleUntil("the third page, no question") { asked() == null && pager.currentItem == 2 }
        assertEquals(2, pager.currentItem)
        swipe(pager, 1)
        assertEquals(1, pager.currentItem)
        idleUntil("the page shown") { viewer.shownImage().drawable != null }
        val (top, bottom) = viewer.shownTop()
        assertEquals(bottom, top, 1f)
    }

    @Test
    fun aSwipeLetGoBeforeItTurnsLeavesATallPageTurnedBackToAtItsBottom() {
        prefs.volumeKeys = true
        val viewer = launch(book("Mixed 05.cbz", listOf(140, 1000, 140)), page = 2)
        val pager = viewer.findViewById<ViewPager2>(R.id.viewer_pager)
        idleUntil("the third page, no question") { asked() == null && pager.currentItem == 2 }
        viewer.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertEquals(1, pager.currentItem)
        idleUntil("the page shown") { viewer.shownImage().drawable != null }
        // A short drag on, let go: the pager springs back.
        pager.beginFakeDrag()
        pager.fakeDragBy(-pager.width * 0.05f)
        pager.endFakeDrag()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
        assertEquals(1, pager.currentItem)
        // Bound again where it is, it stays at its bottom.
        pager.adapter!!.notifyItemChanged(1)
        idle()
        idleUntil("the page shown") { viewer.shownImage().drawable != null }
        val (top, bottom) = viewer.shownTop()
        assertEquals(bottom, top, 1f)
    }

    private fun ViewerActivity.back() {
        onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
    }

    @Test
    fun aBookComeBackToFromTheNextOneShowsItsTallLastPageFromItsBottom() {
        val viewer = launch(book("Mixed 06.cbz", listOf(140, 140, 1000)), page = Int.MAX_VALUE)
        val pager = viewer.findViewById<ViewPager2>(R.id.viewer_pager)
        idleUntil("the last page shown, no question") { asked() == null && pager.currentItem == 2 && viewer.shownImage().drawable != null }
        val (top, bottom) = viewer.shownTop()
        assertEquals(bottom, top, 1f)
    }

    // File decodes run on API 27, where the host decodes them (through BitmapFactory).
    @Test
    @Config(sdk = [27])
    fun aTallLooseImageComeBackToShowsItsBottomAndGoesBackFurtherOnlyFromItsTop() {
        prefs.volumeKeys = true
        // Mostly short images: the folder is not asked about.
        val images = listOf(140, 1000, 140).mapIndexed { i, h -> File(dir, "img${i + 1}.png").apply { writeBytes(TestImages.png(100, h)) } }
        val viewer = launch(images[1], page = Int.MAX_VALUE)
        idleUntil("the image shown, no question") { asked() == null && viewer.shownImage().drawable != null }
        val (top, bottom) = viewer.shownTop()
        assertEquals(bottom, top, 1f)
        // Back scrolls it up a screen at a time; only a back made at its top opens the image before it.
        var presses = 0
        var topBefore = top
        var opened: Intent? = null
        while (opened == null && presses < 50) {
            topBefore = viewer.shownTop().first
            viewer.back()
            presses++
            opened = shadowOf(viewer).nextStartedActivity
        }
        assertEquals(images[0].absolutePath, opened?.getStringExtra(ViewerActivity.EXTRA_PATH))
        assertEquals(Int.MAX_VALUE, opened?.getIntExtra(ViewerActivity.EXTRA_PAGE, -1))
        assertEquals(0f, topBefore, 0.5f)
        assertTrue("$presses presses", presses > 3)
    }

    @Test
    fun aTallPageKeptAsideLandsAtItsBottomSwipedBackAndItsTopSwipedOn() {
        // Opened on the tall page, which stays bound off screen while the next one shows.
        val viewer = launch(book("Mixed 04.cbz", listOf(140, 1000, 140, 140)), page = 1)
        val pager = viewer.findViewById<ViewPager2>(R.id.viewer_pager)
        idleUntil("the second page shown, no question") { asked() == null && pager.currentItem == 1 && viewer.shownImage().drawable != null }
        assertEquals(0f, viewer.shownTop().first, 1f)
        swipe(pager, -1)
        assertEquals(2, pager.currentItem)
        swipe(pager, 1)
        assertEquals(1, pager.currentItem)
        val (top, bottom) = viewer.shownTop()
        assertEquals(bottom, top, 1f)
        // Back further, then on again: from its top, as a tap turning to it.
        swipe(pager, 1)
        assertEquals(0, pager.currentItem)
        swipe(pager, -1)
        assertEquals(1, pager.currentItem)
        assertEquals(0f, viewer.shownTop().first, 1f)
    }
}
