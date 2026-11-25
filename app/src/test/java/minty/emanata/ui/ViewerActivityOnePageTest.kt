package minty.emanata.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager2.widget.ViewPager2
import minty.emanata.R
import minty.emanata.data.Prefs
import minty.emanata.reader.TestImages
import minty.emanata.testing.idleUntil
import org.junit.Assert.assertEquals
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
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A one-page book, as a loose image is, reads as one picture of its folder: no end card,
 * one turn forward opens the next file and one back the previous, whatever the settings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerActivityOnePageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Application
    private lateinit var prefs: Prefs
    private lateinit var dir: File
    private lateinit var images: List<File>

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = Prefs(context)
        // Off: a one-page book must roll on without it.
        prefs.autoNext = false
        dir = tmp.newFolder("images")
        images = (1..3).map { i -> File(dir, "img$i.png").apply { writeBytes(TestImages.png(100, 120)) } }
    }

    private fun book(name: String, pages: Int): File {
        val f = File(dir, name)
        ZipOutputStream(f.outputStream()).use { zip ->
            repeat(pages) {
                zip.putNextEntry(ZipEntry("p%02d.png".format(it)))
                zip.write(byteArrayOf(0))
                zip.closeEntry()
            }
        }
        return f
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun launch(file: File, page: Int = 0): ViewerActivity {
        val intent = Intent(context, ViewerActivity::class.java)
            .putExtra(ViewerActivity.EXTRA_PATH, file.absolutePath)
            .putExtra(ViewerActivity.EXTRA_PAGE, page)
        val viewer = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup().get()
        val progress = viewer.findViewById<View>(R.id.viewer_progress)
        idleUntil("the book open") { progress.visibility != View.VISIBLE }
        // The page indicator is set where the end card is decided: the arrival was handled.
        val pageText = viewer.findViewById<TextView>(R.id.viewer_page_text)
        idleUntil("the page indicator") { pageText.text.isNotEmpty() }
        return viewer
    }

    private fun ViewerActivity.next() {
        findViewById<View>(R.id.btn_next_page).performClick()
        idle()
    }

    private fun ViewerActivity.back() {
        findViewById<View>(R.id.btn_prev_page).performClick()
        idle()
    }

    private fun ViewerActivity.cardShown() = findViewById<View>(R.id.viewer_end).visibility == View.VISIBLE

    private fun ViewerActivity.hintShown() = findViewById<View>(R.id.viewer_hint).visibility == View.VISIBLE

    private fun ViewerActivity.opened(): Intent? = shadowOf(this).nextStartedActivity

    private fun assertOpens(file: File, page: Int, intent: Intent?) {
        assertEquals(file.absolutePath, intent?.getStringExtra(ViewerActivity.EXTRA_PATH))
        assertEquals(page, intent?.getIntExtra(ViewerActivity.EXTRA_PAGE, -1))
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

    @Test
    fun aLooseImageShowsNoEndCardOnArrival() {
        assertEquals(false, launch(images[1]).cardShown())
    }

    @Test
    fun theLastImageShowsNoEndCardEitherWithAutoNextOn() {
        prefs.autoNext = true
        assertEquals(false, launch(images[2]).cardShown())
    }

    @Test
    fun oneTurnForwardOpensTheNextFileWithAutoNextOff() {
        val viewer = launch(images[0])
        viewer.next()
        assertOpens(images[1], 0, viewer.opened())
        assertEquals(false, viewer.cardShown())
    }

    @Test
    fun aSwipeForwardOpensTheNextFileWithAutoNextOff() {
        val viewer = launch(images[0])
        val rv = viewer.list()
        val y = rv.height / 2f
        viewer.drag(rv.width * 0.9f, y, rv.width * 0.1f, y)
        assertOpens(images[1], 0, viewer.opened())
    }

    @Test
    fun aStripFlickUpOpensTheNextFileWithAutoNextOff() {
        prefs.pageMode = "continuous"
        prefs.readingDir = "ttb"
        val viewer = launch(images[0])
        assertEquals(false, viewer.cardShown())
        val rv = viewer.list()
        val x = rv.width / 2f
        viewer.drag(x, rv.height * 0.8f, x, rv.height * 0.2f)
        assertOpens(images[1], 0, viewer.opened())
    }

    @Test
    fun turningForwardOnTheLastFileOnlySaysSo() {
        val viewer = launch(images[2])
        viewer.next()
        assertEquals(context.getString(R.string.finished_last_chapter), ShadowToast.getTextOfLatestToast())
        assertNull(viewer.opened())
        assertEquals(false, viewer.cardShown())
    }

    @Test
    fun aBookAfterTheImagesOpensAtItsStart() {
        val after = book("z.cbz", pages = 4)
        val viewer = launch(images[2])
        viewer.next()
        assertOpens(after, 0, viewer.opened())
    }

    @Test
    fun oneBackOpensThePreviousFileAtItsEnd() {
        val viewer = launch(images[1])
        viewer.back()
        assertOpens(images[0], Int.MAX_VALUE, viewer.opened())
        assertEquals(false, viewer.hintShown())
    }

    @Test
    fun oneBackOpensThePreviousFileEvenWithRepeatedBackDisabled() {
        prefs.prevChapterBacks = 0
        val viewer = launch(images[1])
        viewer.back()
        assertOpens(images[0], Int.MAX_VALUE, viewer.opened())
    }

    @Test
    fun aSwipeBackOpensThePreviousFile() {
        val viewer = launch(images[1])
        val rv = viewer.list()
        val y = rv.height / 2f
        viewer.drag(rv.width * 0.1f, y, rv.width * 0.9f, y)
        assertOpens(images[0], Int.MAX_VALUE, viewer.opened())
    }

    @Test
    fun aBookBeforeTheImagesOpensAtItsEnd() {
        val before = book("a.cbz", pages = 4)
        val viewer = launch(images[0])
        viewer.back()
        assertOpens(before, Int.MAX_VALUE, viewer.opened())
    }

    @Test
    fun backOnTheFirstFileStaysPut() {
        val viewer = launch(images[0])
        // A local file opens at once on the back, so nothing opened by now opens later.
        viewer.back()
        assertEquals(context.getString(R.string.page_indicator, 1, 1), viewer.findViewById<TextView>(R.id.viewer_page_text).text.toString())
        assertNull(viewer.opened())
        assertEquals(false, viewer.hintShown())
    }

    @Test
    fun aOnePageArchiveReadsTheSameWay() {
        val single = book("b.cbz", pages = 1)
        val viewer = launch(single)
        assertEquals(false, viewer.cardShown())
        viewer.next()
        assertOpens(images[0], 0, viewer.opened())
    }

    @Test
    fun aLongerBookStillShowsTheEndCardAtItsLastPage() {
        val longer = book("b.cbz", pages = 2)
        val viewer = launch(longer)
        assertEquals(false, viewer.cardShown())
        viewer.next()
        assertEquals(true, viewer.cardShown())
        assertNull(viewer.opened())
    }
}
