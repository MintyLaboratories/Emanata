package com.infinity.comicreader.ui

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager2.widget.ViewPager2
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ResumeMode
import com.infinity.comicreader.data.SpreadDisplay
import com.infinity.comicreader.data.TwoPage
import com.infinity.comicreader.reader.Fixtures
import com.infinity.comicreader.reader.SyntheticArt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
import java.io.ByteArrayOutputStream
import java.io.File

/** Spreads in two files, told by their seams: shown as one, or paired, in place of the pairing by number. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViewerActivitySeamsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var prefs: Prefs
    private lateinit var dir: File

    private val width = 200
    private val height = 300

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("library", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        prefs = Prefs(context)
        prefs.resumeMode = ResumeMode.NO
        dir = tmp.newFolder("shelf")
    }

    private fun png(pixels: IntArray): ByteArray {
        val bitmap = Bitmap.createBitmap(pixels, width, pixels.size / width, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** [count] pages `Tale 001-000`…, each its own picture, but pages [first] and the next the halves of one. */
    private fun book(count: Int, first: Int): File {
        val art = SyntheticArt.art(width * 2, height, seed = 77)
        val pages = (0 until count).map { i ->
            when (i) {
                first -> SyntheticArt.columns(art, width * 2, 0, width)
                first + 1 -> SyntheticArt.columns(art, width * 2, width, width * 2)
                else -> SyntheticArt.art(width, height, seed = i.toLong())
            }
        }
        return Fixtures.zip(File(dir, "Tale 001.cbz"), *pages.mapIndexed { i, p -> Fixtures.entry("Tale 001-%03d.png".format(i), png(p)) }.toTypedArray())
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun launch(book: File): ViewerActivity {
        val intent = Intent(ApplicationProvider.getApplicationContext(), ViewerActivity::class.java)
            .putExtra(ViewerActivity.EXTRA_PATH, book.absolutePath)
        val viewer = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup().get()
        val progress = viewer.findViewById<View>(R.id.viewer_progress)
        await { progress.visibility != View.VISIBLE }
        return viewer
    }

    private fun await(ms: Long = 10_000, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + ms
        while (!done() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            idle()
        }
    }

    private fun ViewerActivity.items() = findViewById<ViewPager2>(R.id.viewer_pager).adapter!!.itemCount

    private fun ViewerActivity.indicator() = findViewById<TextView>(R.id.viewer_page_text).text.toString()

    private fun ViewerActivity.next() {
        findViewById<View>(R.id.btn_next_page).performClick()
        idle()
    }

    @Test
    fun aSpreadInTwoFilesShowsAsOneAndTurnsTheScreen() {
        val viewer = launch(book(count = 7, first = 3))
        // Merge is the default: pages 4 and 5 become one screen once their seam is checked.
        await { viewer.items() == 6 }
        assertEquals(6, viewer.items())
        repeat(3) { viewer.next() }
        assertEquals("4 / 7", viewer.indicator())
        // Rotate for spreads is on by default.
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, viewer.requestedOrientation)
        viewer.next()
        assertEquals("6 / 7", viewer.indicator())
        assertNotEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, viewer.requestedOrientation)
    }

    @Test
    fun splitKeepsTwoFilesApart() {
        prefs.spreads = SpreadDisplay.SPLIT
        val viewer = launch(book(count = 7, first = 3))
        repeat(50) { Thread.sleep(10); idle() }
        assertEquals(7, viewer.items())
    }

    @Test
    fun twoPagesPairTheHalvesOfOnePicture() {
        prefs.twoPage = TwoPage.ON
        // By number: [000] [001|002] [003|004] [005|006] [007|008]; page 004 runs on into 005.
        val viewer = launch(book(count = 9, first = 4))
        await { viewer.items() == 6 }
        assertEquals(6, viewer.items())
        repeat(3) { viewer.next() }
        assertEquals("5 / 9", viewer.indicator())
    }

    @Test
    fun seamsAreKeptForTheNextOpen() {
        val book = book(count = 7, first = 3)
        val first = launch(book)
        await { first.items() == 6 }
        first.finish()
        val seams = File(ApplicationProvider.getApplicationContext<Application>().cacheDir, "page-seams")
        assertTrue(seams.listFiles()?.any { it.name.endsWith(".bin") } == true)
        // Opened again, the merged screen is there from the start.
        val again = launch(book)
        assertEquals(6, again.items())
    }
}
