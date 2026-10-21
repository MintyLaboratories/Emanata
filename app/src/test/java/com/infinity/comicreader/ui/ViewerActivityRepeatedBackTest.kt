package com.infinity.comicreader.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.SeekBar
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Prefs
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
        prefs.pageMode = "longstrip"
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
    fun inTwoPageModeAtItsLastSpread() {
        prefs.twoPage = true
        // Six pages make three spreads.
        assertEquals(2, launch(first, Int.MAX_VALUE).page())
    }
}
