package com.infinity.comicreader.ui

import android.Manifest
import android.app.Application
import android.os.Environment
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.infinity.comicreader.R
import com.infinity.comicreader.data.DetailDisplay
import com.infinity.comicreader.data.ListDensity
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.SortKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.Duration

/**
 * A long press on the grid/list button fans out the view ring: quick view options for the
 * tab, each showing its value and applying on a tap; the hub, a tap outside or Back close it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w800dp-h1280dp")
class ListActivityViewRingTest {

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
        (1..12).forEach { File(dir, "Book %02d.cbz".format(it)).createNewFile() }
        File(dir, "Folder").mkdirs()
        prefs.lastPath = dir.absolutePath
    }

    private fun settle(ms: Long = 20) = repeat(3) {
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    private fun awaitUntil(what: String, timeoutMs: Long = 5000, done: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            settle()
            if (done()) return
            assertTrue("timed out waiting for $what", System.currentTimeMillis() < end)
            Thread.sleep(10)
        }
    }

    private fun launch(tab: Int = 0): ListActivity {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        activity.awaitListing()
        if (tab != 0) {
            activity.findViewById<TabLayout>(R.id.tabs).getTabAt(tab)!!.select()
            activity.awaitListing()
        }
        return activity
    }

    private fun ListActivity.awaitListing() =
        awaitUntil("the listing") { findViewById<View>(R.id.progress).visibility != View.VISIBLE }

    private fun ListActivity.fab(): FloatingActionButton = findViewById(R.id.fab)

    private fun ListActivity.recycler(): RecyclerView = findViewById(R.id.recycler)

    private fun ListActivity.openRing() {
        assertTrue(fab().performLongClick())
        settle(300)
        assertTrue(viewRing.isOpen)
    }

    private fun ListActivity.tap(option: RingOption) {
        viewRing.button(option)!!.performClick()
        settle()
    }

    private fun ListActivity.labelOf(option: RingOption) = viewRing.label(option)!!.text.toString()

    private fun center(v: View): Pair<Float, Float> {
        val at = IntArray(2).also { v.getLocationInWindow(it) }
        return (at[0] + v.width / 2f) to (at[1] + v.height / 2f)
    }

    @Test
    fun aLongPressFansTheTabsOptionsOutTowardTheInside() {
        val list = launch()
        list.openRing()
        val options = ViewRing.options(Prefs.TAB_BROWSE)
        val (fx, fy) = center(list.fab())
        for (o in options) {
            val b = list.viewRing.button(o)
            assertNotNull("$o", b)
            val (bx, by) = center(b!!)
            // Right-handed: up and to the left of the button, never past it.
            assertTrue("$o x", bx <= fx + 1)
            assertTrue("$o y", by <= fy + 1)
            assertTrue("$o reachable", b.width >= 48 * list.resources.displayMetrics.density)
            assertTrue("$o described", b.contentDescription.isNotEmpty())
        }
        // The hub stands where the button is.
        val (hx, hy) = center(list.viewRing.hub()!!)
        assertEquals(fx, hx, 1f)
        assertEquals(fy, hy, 1f)
        assertEquals("Layout\nGrid", list.labelOf(RingOption.LAYOUT))
        assertEquals("Folders first\nOn", list.labelOf(RingOption.FOLDERS_FIRST))
        assertNull(list.viewRing.button(RingOption.SECTIONS))
    }

    @Test
    fun leftHandedFansToTheRight() {
        prefs.leftHanded = true
        val list = launch()
        settle()
        list.openRing()
        val (fx, _) = center(list.fab())
        for (o in ViewRing.options(Prefs.TAB_BROWSE)) {
            assertTrue("$o", center(list.viewRing.button(o)!!).first >= fx - 1)
        }
    }

    @Test
    fun tapsApplyAtOnceAndTheValuesFollow() {
        val list = launch()
        list.openRing()
        list.tap(RingOption.LAYOUT)
        assertFalse(prefs.view(Prefs.TAB_BROWSE).grid)
        assertFalse(list.recycler().layoutManager is GridLayoutManager)
        assertEquals("Layout\nList", list.labelOf(RingOption.LAYOUT))
        assertEquals("Larger\nNormal", list.labelOf(RingOption.LARGER))

        list.tap(RingOption.LARGER)
        assertEquals(ListDensity.LARGE, prefs.view(Prefs.TAB_BROWSE).density)
        assertEquals("Smaller\nLarge", list.labelOf(RingOption.SMALLER))

        list.tap(RingOption.DETAIL)
        assertEquals(DetailDisplay.DENSE, prefs.view(Prefs.TAB_BROWSE).detail)
        assertEquals("Details\nDense", list.labelOf(RingOption.DETAIL))

        list.tap(RingOption.SORT)
        assertTrue(prefs.sort(Prefs.TAB_BROWSE).by.flipped)
        assertEquals(SortKey.NAME, prefs.sort(Prefs.TAB_BROWSE).by.key)
        assertEquals("Sort\nName – Z → A", list.labelOf(RingOption.SORT))

        list.tap(RingOption.FOLDERS_FIRST)
        assertFalse(prefs.sort(Prefs.TAB_BROWSE).foldersFirst)
        assertTrue(list.viewRing.isOpen)
    }

    @Test
    fun gridSizeStepsAndGraysAtItsEnd() {
        prefs.setGridSize(Prefs.TAB_BROWSE, 5, 0)
        val list = launch()
        list.openRing()
        assertEquals("Smaller\n5 columns", list.labelOf(RingOption.SMALLER))
        list.tap(RingOption.SMALLER)
        assertEquals(6, prefs.view(Prefs.TAB_BROWSE).columns)
        assertEquals(6, (list.recycler().layoutManager as GridLayoutManager).spanCount)
        assertFalse(list.viewRing.button(RingOption.SMALLER)!!.isEnabled)
        list.tap(RingOption.LARGER)
        assertEquals(5, prefs.view(Prefs.TAB_BROWSE).columns)
        assertTrue(list.viewRing.button(RingOption.SMALLER)!!.isEnabled)
    }

    @Test
    fun favoritesToggleSections() {
        val list = launch(tab = 1)
        list.openRing()
        assertEquals("Sections\nOn", list.labelOf(RingOption.SECTIONS))
        list.tap(RingOption.SECTIONS)
        assertFalse(prefs.groupFavorites)
        assertEquals("Sections\nOff", list.labelOf(RingOption.SECTIONS))
        assertNull(list.viewRing.button(RingOption.FOLDERS_FIRST))
    }

    @Test
    fun backTheHubAndTheScrimClose() {
        val list = launch()
        list.openRing()
        @Suppress("DEPRECATION")
        list.onBackPressed()
        assertFalse(list.viewRing.isOpen)
        // Back closed only the ring: still in the same folder.
        assertFalse(list.isFinishing)

        list.openRing()
        list.viewRing.hub()!!.performClick()
        assertFalse(list.viewRing.isOpen)

        list.openRing()
        val scrim = list.viewRing.scrim()!!
        scrim.performClick()
        assertFalse(list.viewRing.isOpen)
        settle(300)
        assertNull(scrim.parent)
    }

    /** Taps the window at [x], [y] as a finger would. */
    private fun ListActivity.tapAt(x: Float, y: Float) {
        val t = android.os.SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val e = MotionEvent.obtain(t, t + 50, action, x, y, 0)
            window.decorView.dispatchTouchEvent(e)
            e.recycle()
        }
    }

    @Test
    fun aQuickTapAfterClosingDoesNotOpenTheBookUnderneath() {
        val list = launch()
        val rows = list.recycler()
        val (x, y) = center(rows.getChildAt(rows.childCount - 1))
        list.openRing()
        val scrim = list.viewRing.scrim()!!
        scrim.performClick()
        assertFalse(list.viewRing.isOpen)
        // Still fading out: the second tap lands on it.
        assertNotNull(scrim.parent)
        list.tapAt(x, y)
        settle(300)
        assertNull(scrim.parent)
        assertNull(shadowOf(list).nextStartedActivity)
        // Once gone, the same tap opens the book.
        list.tapAt(x, y)
        settle()
        assertNotNull(shadowOf(list).nextStartedActivity)
    }

    @Test
    fun aTapWhileOpeningLeavesTheLabelsAsTheirButtons() {
        prefs.setGridSize(Prefs.TAB_BROWSE, 5, 0)
        val list = launch()
        assertTrue(list.fab().performLongClick())
        // Tapped before the labels have faded in.
        list.viewRing.button(RingOption.SMALLER)!!.performClick()
        settle(300)
        assertFalse(list.viewRing.button(RingOption.SMALLER)!!.isEnabled)
        assertEquals(list.viewRing.button(RingOption.SMALLER)!!.alpha, list.viewRing.label(RingOption.SMALLER)!!.alpha, 0.01f)
        assertTrue(list.viewRing.label(RingOption.SMALLER)!!.alpha < 0.5f)
        assertEquals(1f, list.viewRing.label(RingOption.LARGER)!!.alpha, 0.01f)
    }

    @Test
    fun aLongPressOnSortOpensTheSortPopup() {
        val list = launch()
        list.openRing()
        assertTrue(list.viewRing.button(RingOption.SORT)!!.performLongClick())
        settle()
        assertFalse(list.viewRing.isOpen)
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(dialog.isShowing)
    }

    @Test
    fun onlyTheGridListButtonOpensIt() {
        val list = launch()
        val rows = list.recycler()
        settle()
        rows.getChildAt(rows.childCount - 1).performLongClick()
        settle()
        assertTrue(list.selection.active)
        assertFalse(list.fab().performLongClick())
        assertFalse(list.viewRing.isOpen)
    }

    @Test
    fun talkBackNamesTheLongPress() {
        val list = launch()
        val info = AccessibilityNodeInfo.obtain()
        list.fab().onInitializeAccessibilityNodeInfo(info)
        val long = info.actionList.firstOrNull { it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK }
        assertEquals(context.getString(R.string.view_ring_open), long?.label?.toString())
    }
}
