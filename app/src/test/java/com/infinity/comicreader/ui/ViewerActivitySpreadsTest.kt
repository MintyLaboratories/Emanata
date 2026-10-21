package com.infinity.comicreader.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.materialswitch.MaterialSwitch
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ResumeMode
import com.infinity.comicreader.data.SpreadDisplay
import com.infinity.comicreader.data.TwoPage
import com.infinity.comicreader.reader.Fixtures
import com.infinity.comicreader.reader.TestImages
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
import java.io.File

/** Paged reading laid out by the spread engine: pairs by page number, and spreads split or whole. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerActivitySpreadsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Application
    private lateinit var prefs: Prefs
    private lateinit var dir: File

    private val portrait = TestImages.png(26, 40)
    private val wide = TestImages.png(52, 40)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = Prefs(context)
        prefs.resumeMode = ResumeMode.NO
        dir = tmp.newFolder("shelf")
    }

    /** A book of [names] (`.png` added), wide where [wideNames] says. */
    private fun book(names: List<String>, wideNames: Set<String> = emptySet()): File =
        Fixtures.zip(File(dir, "Tale 001.cbz"), *names.map { Fixtures.entry("$it.png", if (it in wideNames) wide else portrait) }.toTypedArray())

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun launch(book: File): ViewerActivity {
        val intent = Intent(context, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_PATH, book.absolutePath)
        val viewer = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup().get()
        val progress = viewer.findViewById<View>(R.id.viewer_progress)
        waitUntil { progress.visibility != View.VISIBLE }
        return viewer
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        do {
            Thread.sleep(10)
            idle()
        } while (!condition() && System.currentTimeMillis() < deadline)
    }

    private fun ViewerActivity.items() = findViewById<ViewPager2>(R.id.viewer_pager).adapter!!.itemCount

    private fun ViewerActivity.indicator() = findViewById<TextView>(R.id.viewer_page_text).text.toString()

    private fun ViewerActivity.next() {
        findViewById<View>(R.id.btn_next_page).performClick()
        idle()
    }

    private fun numbered(from: Int, to: Int) = (from..to).map { "Tale 001-%03d".format(it) }

    @Test
    fun twoPagesPairByPageNumberAroundAFilenameSpread() {
        prefs.twoPage = TwoPage.ON
        val names = numbered(0, 5) + "Tale 001-006-007" + numbered(8, 11)
        val viewer = launch(book(names))
        // [000] [001] [002|003] [004|005] [006-007] [008|009] [010|011]: the spread votes even.
        assertEquals(7, viewer.items())
        repeat(4) { viewer.next() }
        assertEquals("7 / 11", viewer.indicator())
        viewer.next()
        assertEquals("8 / 11", viewer.indicator())
    }

    @Test
    fun theFirstPageStandsAloneEvenWhenWide() {
        prefs.twoPage = TwoPage.ON
        val names = (1..8).map { "Tale %02d".format(it) }
        val viewer = launch(book(names, setOf("Tale 01")))
        assertEquals(5, viewer.items())
        viewer.next()
        assertEquals("2 / 8", viewer.indicator())
    }

    @Test
    fun splitShowsAWidePageAsTwoNumberedPages() {
        prefs.spreads = SpreadDisplay.SPLIT
        val viewer = launch(book(numbered(1, 10), setOf("Tale 001-005")))
        // The sizes are read after the book shows, then the wide page takes two screens.
        waitUntil { viewer.items() == 11 }
        assertEquals(11, viewer.items())
        repeat(4) { viewer.next() }
        assertEquals("5 / 11", viewer.indicator())
        viewer.next()
        assertEquals("6 / 11", viewer.indicator())
        viewer.next()
        assertEquals("7 / 11", viewer.indicator())
    }

    @Test
    fun mergeShowsAWidePageWhole() {
        prefs.spreads = SpreadDisplay.MERGE
        val viewer = launch(book(numbered(1, 10), setOf("Tale 001-005")))
        repeat(30) { Thread.sleep(10); idle() }
        assertEquals(10, viewer.items())
    }

    @Test
    fun theSheetSwitchesSpreadsBetweenSplitAndMerge() {
        val viewer = launch(book(numbered(1, 10), setOf("Tale 001-005")))
        ReaderSheet(viewer).tap(R.id.btn_spreads_split)
        waitUntil { viewer.items() == 11 }
        assertEquals(SpreadDisplay.SPLIT, prefs.spreads)
        assertEquals(11, viewer.items())
        ReaderSheet(viewer).tap(R.id.btn_spreads_merge)
        assertEquals(10, viewer.items())
    }

    @Test
    fun shiftingMovesThePairsOfThisBookOnly() {
        prefs.twoPage = TwoPage.ON
        val book = book((1..7).map { "Tale %02d".format(it) })
        val viewer = launch(book)
        // [01] [02|03] [04|05] [06|07], then shifted [01] [02] [03|04] [05|06] [07].
        assertEquals(4, viewer.items())
        val sheet = ReaderSheet(viewer)
        assertTrue(sheet.view<MaterialSwitch>(R.id.sheet_shift).isEnabled)
        sheet.tap(R.id.sheet_shift)
        assertEquals(5, viewer.items())
        assertTrue(Library(context).isShifted(book.absolutePath))
        sheet.tap(R.id.sheet_shift)
        assertEquals(4, viewer.items())
        assertFalse(Library(context).isShifted(book.absolutePath))
    }
}
