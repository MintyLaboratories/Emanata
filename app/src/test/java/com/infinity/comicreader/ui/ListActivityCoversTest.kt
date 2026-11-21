package com.infinity.comicreader.ui

import com.infinity.comicreader.testing.bookId
import com.infinity.comicreader.testing.coverText
import android.Manifest
import android.app.Application
import android.os.Environment
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.testing.idleUntil
import com.infinity.comicreader.ai.CoverTextDetectors
import com.infinity.comicreader.data.Bookmark
import com.infinity.comicreader.data.CoverShape
import com.infinity.comicreader.data.HistoryItem
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.ListDensity
import com.infinity.comicreader.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/** Cover boxes take the cover shape inside grid cells and the density's size in list rows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w800dp-h1280dp")
class ListActivityCoversTest {

    private lateinit var prefs: Prefs
    private lateinit var dir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("ui", 0).edit().clear().commit()
        prefs = Prefs(context)
        dir = File(Environment.getExternalStorageDirectory(), "books").apply { mkdirs() }
        (1..3).forEach { File(dir, "Issue $it.cbz").createNewFile() }
        prefs.lastPath = dir.absolutePath
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Waits until the folder or tab shown is listed, a folder's look into what it holds included. */
    private fun ListActivity.awaitListed() = idleUntil("the listing") { listJob?.isCompleted == true }

    /** The cover boxes on screen, after a layout of the list. */
    private fun boxes(): List<View> = rows().mapNotNull { it.findViewById(R.id.box) }

    /** The titles on screen, after a layout of the list. */
    private fun titles(): List<String> = rows().mapNotNull { it.findViewById<TitleView>(R.id.title)?.text?.toString() }

    /** The rows on screen, after a layout of the list, on tab [tab]. */
    private fun rows(tab: Int = 0): List<View> {
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        activity.awaitListed()
        if (tab != 0) {
            activity.findViewById<com.google.android.material.tabs.TabLayout>(R.id.tabs).getTabAt(tab)!!.select()
            activity.awaitListed()
        }
        val recycler = activity.findViewById<RecyclerView>(R.id.recycler)
        recycler.measure(
            View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
        )
        recycler.layout(0, 0, 1600, 2400)
        return (0 until recycler.childCount).map { recycler.getChildAt(it) }
    }

    private fun ratio(v: View) = v.width.toFloat() / v.height

    @Test
    fun gridCoversAreTwoByThreeByDefault() {
        prefs.setGridSize(Prefs.TAB_BROWSE, 3, 0)
        val boxes = boxes()
        assertEquals(3, boxes.size)
        boxes.forEach { assertTrue("${it.width}x${it.height}", abs(ratio(it) - 2f / 3f) < 0.01f) }
    }

    @Test
    fun fixedRowsCenterTheShapeInTheCell() {
        prefs.coverShape = CoverShape.WIDE
        prefs.setGridSize(Prefs.TAB_BROWSE, 2, 3)
        val box = boxes().first()
        val cover = box.parent as View
        assertTrue(abs(ratio(box) - 0.75f) < 0.01f)
        // The largest 3:4 box in a wide cover area: full height, centered across.
        assertEquals(cover.height, box.height)
        assertTrue(abs((cover.width - box.width) / 2 - box.left) <= 1)
    }

    @Test
    fun coverSpacingSetsTheGapBetweenCells() {
        prefs.setGridSize(Prefs.TAB_BROWSE, 3, 0)
        prefs.coverSpacing = 20
        val cards = rows()
        val dp = cards[0].resources.displayMetrics.density
        // Two half gaps between neighbors, one at the list's edge.
        assertEquals((10 * dp).toInt(), cards[0].left)
        assertEquals((20 * dp).toInt(), cards[1].left - cards[0].right)
        // The cover sits 4 dp inside its card.
        assertEquals((4 * dp).toInt(), (cards[0].findViewById<View>(R.id.cover).parent as View).paddingLeft)
    }

    @Test
    fun coverSpacingIsClampedAndDefaultsToEight() {
        assertEquals(8, prefs.coverSpacing)
        prefs.coverSpacing = 99
        assertEquals(Prefs.COVER_SPACING_MAX, prefs.coverSpacing)
        prefs.coverSpacing = -3
        assertEquals(0, prefs.coverSpacing)
    }

    @Test
    fun listThumbnailsFollowTheDensity() {
        prefs.setGrid(Prefs.TAB_BROWSE, false)
        prefs.setDensity(Prefs.TAB_BROWSE, ListDensity.LARGE)
        val box = boxes().first()
        val dp = box.resources.displayMetrics.density
        assertEquals((120 * dp).toInt(), box.height)
        assertEquals((80 * dp).toInt(), box.width)
    }

    /** The first row's badge in a Browse list at [density], Issue 1 being on page 100 of 400. */
    private fun badgeAt(density: ListDensity): TextView {
        val issue = File(dir, "Issue 1.cbz")
        Library(ApplicationProvider.getApplicationContext()).addHistory(HistoryItem(issue.absolutePath, issue.name, 99, 400, 1L, bookId(issue)))
        prefs.setGrid(Prefs.TAB_BROWSE, false)
        prefs.setDensity(Prefs.TAB_BROWSE, density)
        return rows().mapNotNull { it.findViewById<TextView>(R.id.badge) }.first()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun narrowListBadgesShorten() {
        assertEquals("100 / 400", badgeAt(ListDensity.LARGE).text.toString())
        for (density in listOf(ListDensity.NORMAL, ListDensity.COMPACT)) {
            val badge = badgeAt(density)
            assertEquals(View.VISIBLE, badge.visibility)
            assertTrue(badge.text.toString(), badge.text.isEmpty() || badge.text.toString() == "25%")
        }
    }

    @Test
    fun bookmarksShowNoBadgeOverTheirPage() {
        val issue = File(dir, "Issue 1.cbz")
        val context = ApplicationProvider.getApplicationContext<Application>()
        Library(context).addHistory(HistoryItem(issue.absolutePath, issue.name, 99, 400, 1L, bookId(issue)))
        Library(context).addBookmark(Bookmark(issue.absolutePath, issue.name, 40, "", 1L, bookId(issue)))
        val badges = rows(tab = 3).mapNotNull { it.findViewById<TextView>(R.id.badge) }
        assertEquals(1, badges.size)
        assertEquals(View.GONE, badges[0].visibility)
    }

    /** The titles shown once a background look into the folder has landed. */
    private fun settledTitles(): List<String> {
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        activity.awaitListed()
        val recycler = activity.findViewById<RecyclerView>(R.id.recycler)
        recycler.measure(
            View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
        )
        recycler.layout(0, 0, 1600, 2400)
        return (0 until recycler.childCount).mapNotNull {
            recycler.getChildAt(it).findViewById<TitleView>(R.id.title)?.text?.toString()
        }
    }

    @Test
    fun aSetWithManySubfoldersIsShortenedOnceLookedInto() {
        for (name in listOf("Issue A", "Issue B", "Issue C", "Issue D")) File(dir, name).mkdirs()
        assertTrue(settledTitles().containsAll(listOf("1.cbz", "A")))
    }

    @Test
    fun aLibraryKeepsItsTitles() {
        for (name in listOf("Issue A", "Issue B", "Issue C", "Issue D")) {
            File(File(dir, name).apply { mkdirs() }, "one.cbz").createNewFile()
        }
        assertTrue(settledTitles().containsAll(listOf("Issue 1.cbz", "Issue A")))
    }

    @Test
    fun titlesInsideASetLeaveOutTheSeriesName() {
        assertEquals(listOf("1.cbz", "2.cbz", "3.cbz"), titles())
        prefs.shortenPrefixes = false
        assertEquals(listOf("Issue 1.cbz", "Issue 2.cbz", "Issue 3.cbz"), titles())
    }

    @Test
    fun coversAreMadeAgainWhenTheDetectorsModelArrives() {
        val detectors = CoverTextDetectors { coverText("ppocr") { null } }
        CoverTextDetectors.standIn(detectors)
        try {
            val recycler = rows().first().parent as RecyclerView
            val redrawn = intArrayOf(0)
            recycler.adapter!!.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() {
                    redrawn[0]++
                }
            })
            idle()
            val before = redrawn[0]
            detectors.modelArrived()
            idle()
            assertEquals(before + 1, redrawn[0])
        } finally {
            CoverTextDetectors.standIn(null)
        }
    }
}
