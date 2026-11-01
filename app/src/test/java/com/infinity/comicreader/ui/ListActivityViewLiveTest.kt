package com.infinity.comicreader.ui

import android.Manifest
import android.app.Application
import android.content.SharedPreferences
import android.os.Environment
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.slider.Slider
import com.google.android.material.tabs.TabLayout
import com.infinity.comicreader.R
import com.infinity.comicreader.data.BookId
import com.infinity.comicreader.data.Bookmark
import com.infinity.comicreader.data.CoverShape
import com.infinity.comicreader.data.DetailDisplay
import com.infinity.comicreader.data.HistoryItem
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.ListDensity
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.SettingsRepository
import com.infinity.comicreader.data.SortKey
import com.infinity.comicreader.data.SortLevel
import com.infinity.comicreader.data.TabSort
import com.infinity.comicreader.data.TitleDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.time.Duration
import kotlin.math.abs

/**
 * Every view setting shows on screen at once, in the tab it belongs to, as laid out
 * rows: the grid/list button and the ⋮ view items, the row look from Settings, order
 * and sections. No tab switch in between; the place in the list is kept.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w800dp-h1280dp")
class ListActivityViewLiveTest {

    private lateinit var context: Application
    private lateinit var prefs: Prefs
    private lateinit var dir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        for (name in listOf("ui", "library", "favorite_kinds")) context.getSharedPreferences(name, 0).edit().clear().commit()
        prefs = Prefs(context)
        dir = File(Environment.getExternalStorageDirectory(), "shelf").apply { mkdirs() }
        (1..40).forEach { File(dir, "Book %02d.cbz".format(it)).createNewFile() }
        prefs.lastPath = dir.absolutePath
    }

    /** Runs what is due on the main thread, frames included. */
    private fun settle() = repeat(3) {
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
    }

    /** Runs the main thread until [done], while listing goes on in the background; fails after [timeoutMs]. */
    private fun awaitUntil(what: String, timeoutMs: Long = 5000, done: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            settle()
            if (done()) return
            assertTrue("timed out waiting for $what", System.currentTimeMillis() < end)
            Thread.sleep(10)
        }
    }

    /** Waits until [list] has [count] items or more. */
    private fun awaitRows(list: ListActivity, count: Int) =
        awaitUntil("$count items") { (list.recycler().adapter?.itemCount ?: 0) >= count }

    private fun launch(tab: Int = 0): ListActivity {
        // Touched, as on a phone or tablet: no row takes focus and scrolls itself into view.
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        settle()
        if (tab != 0) {
            activity.findViewById<TabLayout>(R.id.tabs).getTabAt(tab)!!.select()
            settle()
        }
        return activity
    }

    private fun ListActivity.recycler(): RecyclerView = findViewById(R.id.recycler)

    /** The book rows laid out on screen, headers and sections left out. */
    private fun ListActivity.rows(): List<View> {
        val r = recycler()
        return (0 until r.childCount).map { r.getChildAt(it) }.filter { it.findViewById<View>(R.id.title) is TitleView }
    }

    /** True when the screen shows grid cells: several rows side by side. */
    private fun ListActivity.showsGrid(): Boolean {
        val rows = rows()
        assertTrue("nothing laid out", rows.isNotEmpty())
        return rows.all { it.width < recycler().width / 2 }
    }

    private fun ListActivity.titles(): List<String> = rows().map { it.findViewById<TitleView>(R.id.title).text.toString() }

    private fun ListActivity.menuItem(id: Int) = findViewById<MaterialToolbar>(R.id.toolbar).menu.findItem(id)

    private fun ListActivity.tapFab() {
        findViewById<FloatingActionButton>(R.id.fab).performClick()
        settle()
    }

    private fun addToEveryList() {
        val library = Library(context)
        library.toggleFavoriteFolder(dir.absolutePath)
        for (i in 1..3) {
            val book = File(dir, "Book %02d.cbz".format(i))
            library.addHistory(HistoryItem(book.absolutePath, book.name, 1, 10, i.toLong(), BookId(0x1000L + i)))
            library.addBookmark(Bookmark(book.absolutePath, book.name, 1, "", i.toLong(), BookId(0x1000L + i)))
        }
    }

    // ------------------------------------------------------------------ grid or list

    @Test
    fun theButtonSwitchesGridAndListOnScreenInEveryTab() {
        addToEveryList()
        for (tab in 0..3) {
            val list = launch(tab)
            val before = list.showsGrid()
            list.tapFab()
            assertEquals("tab $tab", !before, list.showsGrid())
            assertEquals("tab $tab", !before, list.menuItem(R.id.menu_grid_size).isVisible)
            list.tapFab()
            assertEquals("tab $tab", before, list.showsGrid())
        }
    }

    @Test
    fun theMenuItemSwitchesGridAndListOnScreen() {
        val list = launch()
        assertTrue(list.showsGrid())
        list.onOptionsItemSelected(list.menuItem(R.id.menu_grid))
        settle()
        assertFalse(list.showsGrid())
        assertTrue(list.menuItem(R.id.menu_density).isVisible)
        assertFalse(list.menuItem(R.id.menu_grid_size).isVisible)
    }

    @Test
    fun theButtonAppliesBeforeAnyFrame() {
        val list = launch()
        list.findViewById<FloatingActionButton>(R.id.fab).performClick()
        // Set up at once, not on a later turn of the settings feed.
        assertFalse((list.recycler().adapter as BookAdapter).grid)
        assertFalse(list.recycler().layoutManager is GridLayoutManager)
    }

    /** The row shown first: its first item, where it starts below the list's top and its height, margins included. */
    private fun ListActivity.firstRow(): Triple<Int, Int, Int> {
        val r = recycler()
        val lm = r.layoutManager as LinearLayoutManager
        val first = lm.findFirstVisibleItemPosition()
        val v = lm.findViewByPosition(first)!!
        return Triple(first, outerTop(v) - r.paddingTop, outerHeight(v))
    }

    /** Scrolls the grid with [rows] rows per screen so most of its second row is above the top. */
    private fun ListActivity.scrollMostOfARowOff(rows: Int): Triple<Int, Int, Int> {
        prefs.setGridSize(Prefs.TAB_BROWSE, 4, rows)
        settle()
        val r = recycler()
        (r.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(5, 0)
        settle()
        r.scrollBy(0, firstRow().third * 4 / 5)
        settle()
        return firstRow()
    }

    @Test
    fun switchingKeepsTheShareOfTheFirstRowAboveTheTop() {
        val list = launch()
        // Two grid rows per screen: a cell is far taller than a list row.
        val (first, top, height) = list.scrollMostOfARowOff(rows = 2)
        assertTrue(first > 0)
        assertTrue(-top > height / 2)
        list.tapFab()
        val (listed, listTop, rowHeight) = list.firstRow()
        assertEquals(first, listed)
        assertTrue("$rowHeight vs $height", rowHeight < height)
        // Some of the row still shows, as much of it above the top as before.
        assertTrue(listTop > -rowHeight)
        assertEquals(top.toFloat() / height, listTop.toFloat() / rowHeight, 1.5f / rowHeight)
        list.tapFab()
        assertEquals(Triple(first, top, height), list.firstRow())
    }

    @Test
    fun aRoundTripThroughTheGridComesBackToTheSameRow() {
        prefs.setGrid(Prefs.TAB_BROWSE, false)
        prefs.setGridSize(Prefs.TAB_BROWSE, 4, 0)
        val list = launch()
        val r = list.recycler()
        val lm = r.layoutManager as LinearLayoutManager
        // A row that is not first in its grid row.
        lm.scrollToPositionWithOffset(7, -30)
        settle()
        list.tapFab()
        assertTrue(r.layoutManager is GridLayoutManager)
        list.tapFab()
        val back = r.layoutManager as LinearLayoutManager
        assertEquals(7, back.findFirstVisibleItemPosition())
        assertEquals(-30, outerTop(back.findViewByPosition(7)!!) - r.paddingTop)
    }

    @Test
    fun scrollingForgetsThePlaceToSwitchBackTo() {
        prefs.setGrid(Prefs.TAB_HISTORY, false)
        prefs.setGridSize(Prefs.TAB_HISTORY, 4, 0)
        val library = Library(context)
        for (i in 1..40) {
            val book = File(dir, "Book %02d.cbz".format(i))
            library.addHistory(HistoryItem(book.absolutePath, book.name, 1, 10, i.toLong(), BookId(0x2000L + i)))
        }
        val list = launch(tab = 2)
        val r = list.recycler()
        awaitRows(list, 40)
        // Past the first grid row, so the grid is not at its top.
        (r.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(9, 0)
        settle()
        list.tapFab()
        r.scrollBy(0, -100_000)
        settle()
        list.tapFab()
        assertEquals(Triple(0, 0, list.firstRow().third), list.firstRow())
    }

    @Test
    fun reorderingForgetsThePlaceToSwitchBackTo() {
        prefs.setGrid(Prefs.TAB_BROWSE, false)
        prefs.setGridSize(Prefs.TAB_BROWSE, 4, 0)
        val list = launch()
        val r = list.recycler()
        (r.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(7, 0)
        settle()
        list.tapFab()
        prefs.setSort(Prefs.TAB_BROWSE, TabSort(SortLevel(SortKey.NAME, flipped = true)))
        settle()
        val gridFirst = list.firstRow().first
        assertNotEquals(7, gridFirst)
        list.tapFab()
        assertEquals(gridFirst, list.firstRow().first)
    }

    /** Where [v]'s row starts, its margin included. */
    private fun outerTop(v: View): Int = v.top - (v.layoutParams as ViewGroup.MarginLayoutParams).topMargin

    // ------------------------------------------------------------------ grid dimensions

    @Test
    fun gridDimensionsApplyOnScreen() {
        prefs.setGridSize(Prefs.TAB_BROWSE, 3, 0)
        val list = launch()
        val inner = list.recycler().let { it.width - it.paddingLeft - it.paddingRight }
        list.onOptionsItemSelected(list.menuItem(R.id.menu_grid_size))
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.findViewById<GridPicker>(R.id.grid_size)!!.onPick!!.invoke(5, 2)
        settle()
        val cells = list.rows()
        assertEquals(5, (list.recycler().layoutManager as GridLayoutManager).spanCount)
        assertTrue(cells.map { outerWidth(it) }.toString(), cells.all { abs(outerWidth(it) - inner / 5) <= 2 })
        val cellHeight = (list.recycler().adapter as BookAdapter).cellHeight
        assertTrue(cellHeight > 0)
        assertTrue(cells.map { it.height }.toString() + " vs $cellHeight", cells.all { abs(outerHeight(it) - cellHeight) <= 2 })
    }

    private fun outerWidth(v: View): Int = (v.layoutParams as ViewGroup.MarginLayoutParams).let { v.width + it.leftMargin + it.rightMargin }

    private fun outerHeight(v: View): Int = (v.layoutParams as ViewGroup.MarginLayoutParams).let { v.height + it.topMargin + it.bottomMargin }

    // ------------------------------------------------------------------ list density

    @Test
    fun densityAppliesOnScreen() {
        prefs.setGrid(Prefs.TAB_HISTORY, false)
        addToEveryList()
        val list = launch(tab = 2)
        val heightBefore = list.rows().first().findViewById<View>(R.id.box).height
        list.onOptionsItemSelected(list.menuItem(R.id.menu_density))
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.findViewById<Slider>(R.id.density_slider)!!.value = ListDensity.HUGE.step.toFloat()
        settle()
        val box = list.rows().first().findViewById<View>(R.id.box)
        assertNotEquals(heightBefore, box.height)
        assertTrue(box.height > heightBefore)
    }

    // ------------------------------------------------------------------ detail display

    @Test
    fun detailDisplayAppliesOnScreen() {
        prefs.setGrid(Prefs.TAB_BROWSE, false)
        val list = launch()
        assertEquals(View.VISIBLE, list.rows().first().findViewById<TextView>(R.id.subtitle).visibility)
        list.onOptionsItemSelected(list.menuItem(R.id.menu_detail))
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.listView.performItemClick(null, DetailDisplay.HIDDEN.ordinal, 0)
        settle()
        assertTrue(list.rows().all { it.findViewById<TextView>(R.id.subtitle).visibility == View.GONE })
    }

    // ------------------------------------------------------------------ row look from Settings

    @Test
    fun coverShapeTitlesAndSpacingApplyOnScreen() {
        prefs.setGridSize(Prefs.TAB_BROWSE, 4, 0)
        val list = launch()
        fun ratio() = list.rows().first().findViewById<View>(R.id.box).let { it.width.toFloat() / it.height }
        assertTrue(abs(ratio() - 2f / 3f) < 0.02f)
        prefs.coverShape = CoverShape.WIDE
        settle()
        assertTrue(abs(ratio() - 3f / 4f) < 0.02f)
        prefs.titleDisplay = TitleDisplay.HIDDEN
        settle()
        assertTrue(list.rows().all { it.findViewById<TitleView>(R.id.title).visibility == View.GONE || it.findViewById<TitleView>(R.id.title).mode == TitleDisplay.HIDDEN })
        val leftBefore = list.rows().first().left
        prefs.coverSpacing = 20
        settle()
        assertNotEquals(leftBefore, list.rows().first().left)
    }

    // ------------------------------------------------------------------ order and sections

    @Test
    fun sortAppliesOnScreen() {
        val list = launch()
        assertTrue(list.titles().first().endsWith("01.cbz"))
        prefs.setSort(Prefs.TAB_BROWSE, TabSort(SortLevel(SortKey.NAME, flipped = true)))
        settle()
        assertTrue(list.titles().first().endsWith("40.cbz"))
    }

    @Test
    fun sectionsApplyOnScreenAndRowsRefit() {
        val library = Library(context)
        for (name in listOf("Alpha", "Beta", "Gamma")) {
            val folder = File(dir.parentFile, name).apply { mkdirs() }
            File(folder, "one.cbz").createNewFile()
            library.toggleFavoriteFolder(folder.absolutePath)
        }
        library.toggleFavoriteFolder(File(dir, "Book 01.cbz").absolutePath)
        prefs.setGridSize(Prefs.TAB_FAVORITES, 3, 2)
        val list = launch(tab = 1)
        repeat(10) { settle(); Thread.sleep(20) }
        val r = list.recycler()
        fun sections() = (0 until r.childCount).count { r.getChildAt(it).findViewById<View>(R.id.title) !is TitleView }
        fun fits() = 2 * (r.adapter as BookAdapter).cellHeight + sectionHeight(r) * sections() <=
            r.height - r.paddingTop - r.paddingBottom
        assertTrue(sections() > 0)
        assertTrue(fits())
        prefs.groupFavorites = false
        repeat(5) { settle(); Thread.sleep(20) }
        assertEquals(0, sections())
        // Without section headers the two rows take the whole height.
        val rowSpace = r.height - r.paddingTop - r.paddingBottom
        assertTrue(rowSpace - 2 * (r.adapter as BookAdapter).cellHeight < rowSpace / 10)
    }

    private fun sectionHeight(r: RecyclerView): Int =
        (0 until r.childCount).map { r.getChildAt(it) }.firstOrNull { it.findViewById<View>(R.id.title) !is TitleView }?.height ?: 0

    // ------------------------------------------------------------------ hidden files

    @Test
    fun hiddenFilesApplyOnScreen() {
        File(dir, ".Book 00.cbz").createNewFile()
        val list = launch()
        assertFalse(list.titles().any { it.startsWith(".") })
        list.onOptionsItemSelected(list.menuItem(R.id.menu_show_hidden))
        repeat(5) { settle(); Thread.sleep(20) }
        assertTrue(list.titles().any { it.startsWith(".") })
    }

    // ------------------------------------------------------------------ without the settings feed

    /** Stops the settings feed from hearing changes, as if it fell behind the stored settings. */
    private fun muteFeed() {
        val repo = SettingsRepository.get(context)
        val listener = SettingsRepository::class.java.getDeclaredField("listener").apply { isAccessible = true }.get(repo)
        PreferenceManager.getDefaultSharedPreferences(context)
            .unregisterOnSharedPreferenceChangeListener(listener as SharedPreferences.OnSharedPreferenceChangeListener)
    }

    /** Taps the row or chip labeled [text] in [root]. */
    private fun tapText(root: View, text: String) {
        fun find(v: View): View? = when {
            v is TextView && v.text.toString() == text -> v
            v is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        var v: View? = find(root) ?: throw AssertionError("no \"$text\"")
        while (v != null && !v.isClickable) v = v.parent as? View
        v!!.performClick()
    }

    private fun latestDialogRoot(): View = ShadowDialog.getLatestDialog().window!!.decorView

    private fun feedWarnings() = ShadowLog.getLogsForTag("ListActivity").count { it.msg.startsWith("Settings feed behind") }

    @Test
    fun theFeedKeepsUpWithChangesMadeOnScreen() {
        val list = launch()
        list.tapFab()
        list.tapFab()
        list.onOptionsItemSelected(list.menuItem(R.id.menu_show_hidden))
        settle()
        assertEquals(0, feedWarnings())
    }

    @Test
    fun aFeedFallenBehindIsLogged() {
        val list = launch()
        muteFeed()
        list.tapFab()
        assertTrue(feedWarnings() > 0)
    }

    @Test
    fun theButtonSwitchesWithoutTheFeedInEveryTab() {
        addToEveryList()
        for (tab in 0..3) {
            val list = launch(tab)
            muteFeed()
            val before = list.showsGrid()
            list.tapFab()
            assertEquals("tab $tab", !before, list.showsGrid())
            list.tapFab()
            assertEquals("tab $tab", before, list.showsGrid())
        }
    }

    @Test
    fun viewItemsApplyWithoutTheFeed() {
        prefs.setGridSize(Prefs.TAB_BROWSE, 3, 0)
        val list = launch()
        muteFeed()
        list.onOptionsItemSelected(list.menuItem(R.id.menu_grid_size))
        (ShadowDialog.getLatestDialog() as AlertDialog).findViewById<GridPicker>(R.id.grid_size)!!.onPick!!.invoke(5, 2)
        settle()
        assertEquals(5, (list.recycler().layoutManager as GridLayoutManager).spanCount)
        list.onOptionsItemSelected(list.menuItem(R.id.menu_grid))
        settle()
        assertFalse(list.showsGrid())
        val heightBefore = list.rows().first().findViewById<View>(R.id.box).height
        list.onOptionsItemSelected(list.menuItem(R.id.menu_density))
        (ShadowDialog.getLatestDialog() as AlertDialog).findViewById<Slider>(R.id.density_slider)!!.value = ListDensity.HUGE.step.toFloat()
        settle()
        assertTrue(list.rows().first().findViewById<View>(R.id.box).height > heightBefore)
        list.onOptionsItemSelected(list.menuItem(R.id.menu_detail))
        (ShadowDialog.getLatestDialog() as AlertDialog).listView.performItemClick(null, DetailDisplay.HIDDEN.ordinal, 0)
        settle()
        assertTrue(list.rows().all { it.findViewById<TextView>(R.id.subtitle).visibility == View.GONE })
    }

    @Test
    fun theSortPopupAppliesWithoutTheFeed() {
        val list = launch()
        muteFeed()
        assertTrue(list.titles().first().endsWith("01.cbz"))
        list.onOptionsItemSelected(list.menuItem(R.id.menu_sort))
        settle()
        // Name is picked: tapping it flips the order.
        tapText(latestDialogRoot(), context.getString(R.string.sort_name))
        settle()
        assertTrue(list.titles().first().endsWith("40.cbz"))
    }

    @Test
    fun theFilterPopupAppliesWithoutTheFeed() {
        File(dir, "Box").mkdirs()
        val list = launch()
        muteFeed()
        awaitUntil("the folder") { list.titles().contains("Box") }
        list.onOptionsItemSelected(list.menuItem(R.id.menu_filter))
        settle()
        tapText(latestDialogRoot(), context.getString(R.string.filter_folders))
        settle()
        assertEquals(listOf("Box"), list.titles())
    }

    @Test
    fun hiddenFilesShowWithoutTheFeed() {
        File(dir, ".Book 00.cbz").createNewFile()
        val list = launch()
        muteFeed()
        awaitUntil("the listing") { list.titles().isNotEmpty() }
        assertFalse(list.titles().any { it.startsWith(".") })
        list.onOptionsItemSelected(list.menuItem(R.id.menu_show_hidden))
        awaitUntil("hidden files") { list.titles().any { it.startsWith(".") } }
    }

    @Test
    fun changesMadeElsewhereShowOnReturnWithoutTheFeed() {
        val library = Library(context)
        for (name in listOf("Alpha", "Beta")) {
            val folder = File(dir.parentFile, name).apply { mkdirs() }
            File(folder, "one.cbz").createNewFile()
            library.toggleFavoriteFolder(folder.absolutePath)
        }
        library.toggleFavoriteFolder(File(dir, "Book 01.cbz").absolutePath)
        val controller = Robolectric.buildActivity(ListActivity::class.java).setup()
        val list = controller.get()
        settle()
        list.findViewById<TabLayout>(R.id.tabs).getTabAt(1)!!.select()
        val r = list.recycler()
        fun sections() = (0 until r.childCount).count { r.getChildAt(it).findViewById<View>(R.id.title) !is TitleView }
        awaitUntil("sections") { sections() > 0 }
        muteFeed()
        // As if changed in Settings: the screen is out of sight meanwhile.
        controller.pause().stop()
        prefs.groupFavorites = false
        prefs.coverShape = CoverShape.WIDE
        prefs.setGrid(Prefs.TAB_FAVORITES, !prefs.view(Prefs.TAB_FAVORITES).grid)
        val grid = prefs.view(Prefs.TAB_FAVORITES).grid
        controller.start().resume()
        awaitUntil("no sections") { sections() == 0 && list.rows().isNotEmpty() }
        assertEquals(grid, list.showsGrid())
        assertEquals(CoverShape.WIDE, (r.adapter as BookAdapter).options.shape)
    }
}
