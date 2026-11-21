package com.infinity.comicreader.ui

import android.Manifest
import android.app.Application
import android.os.Environment
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.infinity.comicreader.R
import com.infinity.comicreader.testing.idleUntil
import com.infinity.comicreader.data.DetailDisplay
import com.infinity.comicreader.data.ListDensity
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.RingOption
import com.infinity.comicreader.data.RingOptions
import com.infinity.comicreader.data.SortKey
import com.infinity.comicreader.data.TitleDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.Duration
import kotlin.math.hypot

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
    private lateinit var controller: ActivityController<ListActivity>

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
        // Layout first, then the default ring: Browse shows six.
        prefs.ringOptions = RingOptions.DEFAULT.with(RingOption.SECTIONS, false).with(RingOption.LAYOUT, true).moved(6, 0)
    }

    private fun settle(ms: Long = 20) = repeat(3) {
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }


    private fun launch(tab: Int = 0, touch: Boolean = true): ListActivity {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(touch)
        controller = Robolectric.buildActivity(ListActivity::class.java).setup()
        val activity = controller.get()
        activity.awaitListing()
        if (tab != 0) {
            activity.findViewById<TabLayout>(R.id.tabs).getTabAt(tab)!!.select()
            activity.awaitListing()
        }
        return activity
    }

    /** Runs the main thread, frames included, until the tab is listed. */
    private fun ListActivity.awaitListing() = idleUntil("the listing", step = { settle() }) { listJob?.isCompleted == true }

    private fun ListActivity.fab(): FloatingActionButton = findViewById(R.id.fab)

    private fun ListActivity.recycler(): RecyclerView = findViewById(R.id.recycler)

    /** A status bar and side cutouts the ring keeps clear of, in px. */
    private val ListActivity.bars: Insets
        get() = resources.displayMetrics.density.let { d -> Insets.of((24 * d).toInt(), (32 * d).toInt(), (24 * d).toInt(), 0) }

    private fun ListActivity.openRing() {
        viewRing.barInsets = { bars }
        assertTrue(fab().performLongClick())
        settle(300)
        assertTrue(viewRing.isOpen)
    }

    private fun ListActivity.tap(option: RingOption) {
        viewRing.button(option)!!.performClick()
        settle()
    }

    /** [option]'s label as read, without the break the value may take after its arrow. */
    private fun ListActivity.labelOf(option: RingOption) = viewRing.label(option)!!.text.toString().replace("\u200B", "")

    private fun center(v: View): Pair<Float, Float> {
        val at = IntArray(2).also { v.getLocationInWindow(it) }
        return (at[0] + v.width / 2f) to (at[1] + v.height / 2f)
    }

    /** No work of this test's screens runs on into the next. */
    @After
    fun awaitLeftovers() = awaitLeftoverWork()

    @Test
    fun aLongPressFansTheTabsOptionsOutTowardTheInside() {
        val list = launch()
        list.openRing()
        val options = ViewRing.options(Prefs.TAB_BROWSE, prefs.ringOptions)
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
        assertEquals("Layout\nGrid→List", list.labelOf(RingOption.LAYOUT))
        assertEquals("Folders first\nOn→Off", list.labelOf(RingOption.FOLDERS_FIRST))
        assertNull(list.viewRing.button(RingOption.SECTIONS))
    }

    private fun box(v: View): RingGeometry.Box {
        val at = IntArray(2).also { v.getLocationInWindow(it) }
        return RingGeometry.Box(at[0].toFloat(), at[1].toFloat(), (at[0] + v.width).toFloat(), (at[1] + v.height).toFloat())
    }

    /** Every label shows whole on screen inside the bars, clear of every round button, the hub and each other. */
    private fun ListActivity.assertLabelsClear(tab: String) {
        val screen = box(window.decorView).let { RingGeometry.Box(it.left + bars.left, it.top + bars.top, it.right - bars.right, it.bottom - bars.bottom) }
        val round = (ViewRing.options(tab, prefs.ringOptions).map { viewRing.button(it)!! } + viewRing.hub()!!).map { b ->
            center(b).let { (x, y) -> Triple(x, y, b.width / 2f) }
        }
        val labels = ViewRing.options(tab, prefs.ringOptions).map { it to box(viewRing.label(it)!!) }
        for ((o, l) in labels) {
            assertTrue("$o $l on $screen", l.within(screen))
            for ((x, y, r) in round) assertEquals("$o $l on $x, $y", 0f, l.overlap(x, y, r), 0f)
            for ((p, other) in labels) if (p != o) assertEquals("$o on $p", 0f, l.overlap(other), 0f)
        }
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun labelsStayClearInANarrowWindow() {
        val list = launch()
        list.openRing()
        val full = RingGeometry.radius(ViewRing.options(Prefs.TAB_BROWSE, prefs.ringOptions).size, 112f, 136f)
        val (fx, fy) = center(list.fab())
        val (lx, ly) = center(list.viewRing.button(RingOption.FOLDERS_FIRST)!!)
        // The ring shrank to fit its labels.
        assertTrue(hypot(fx - lx, fy - ly) < full * list.resources.displayMetrics.density)
        list.assertLabelsClear(Prefs.TAB_BROWSE)
        // Values that change width keep clear too.
        list.tap(RingOption.LAYOUT)
        list.tap(RingOption.SORT)
        list.assertLabelsClear(Prefs.TAB_BROWSE)
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun leftHandedLabelsStayClearInANarrowWindow() {
        prefs.leftHanded = true
        val list = launch()
        settle()
        list.openRing()
        val (fx, _) = center(list.fab())
        for (o in ViewRing.options(Prefs.TAB_BROWSE, prefs.ringOptions)) assertTrue("$o", center(list.viewRing.button(o)!!).first >= fx - 1)
        list.assertLabelsClear(Prefs.TAB_BROWSE)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aRoomyWindowKeepsEveryLabelOutward() {
        val list = launch()
        list.openRing()
        list.assertLabelsClear(Prefs.TAB_BROWSE)
        val (fx, fy) = center(list.fab())
        for (o in ViewRing.options(Prefs.TAB_BROWSE, prefs.ringOptions)) {
            val (bx, by) = center(list.viewRing.button(o)!!)
            val (lx, ly) = center(list.viewRing.label(o)!!)
            // Outward: farther from the hub than its button.
            assertTrue("$o", hypot(lx - fx, ly - fy) > hypot(bx - fx, by - fy))
        }
    }

    @Test
    fun leftHandedFansToTheRight() {
        prefs.leftHanded = true
        val list = launch()
        settle()
        list.openRing()
        val (fx, _) = center(list.fab())
        for (o in ViewRing.options(Prefs.TAB_BROWSE, prefs.ringOptions)) {
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
        assertEquals("Layout\nList→Grid", list.labelOf(RingOption.LAYOUT))
        assertEquals("Larger\nNormal→Large", list.labelOf(RingOption.LARGER))

        list.tap(RingOption.LARGER)
        assertEquals(ListDensity.LARGE, prefs.view(Prefs.TAB_BROWSE).density)
        assertEquals("Smaller\nLarge→Normal", list.labelOf(RingOption.SMALLER))

        list.tap(RingOption.DETAIL)
        assertEquals(DetailDisplay.DENSE, prefs.view(Prefs.TAB_BROWSE).detail)
        assertEquals("Details\nDense→Hidden", list.labelOf(RingOption.DETAIL))

        list.tap(RingOption.SORT)
        assertTrue(prefs.sort(Prefs.TAB_BROWSE).by.flipped)
        assertEquals(SortKey.NAME, prefs.sort(Prefs.TAB_BROWSE).by.key)
        assertEquals("Sort\nName ↓→Name ↑", list.labelOf(RingOption.SORT))

        list.tap(RingOption.FOLDERS_FIRST)
        assertFalse(prefs.sort(Prefs.TAB_BROWSE).foldersFirst)
        assertTrue(list.viewRing.isOpen)
    }

    @Test
    fun gridSizeStepsAndGraysAtItsEnd() {
        prefs.setGridSize(Prefs.TAB_BROWSE, 5, 0)
        val list = launch()
        list.openRing()
        assertEquals("Smaller\n5 columns→6 columns", list.labelOf(RingOption.SMALLER))
        list.tap(RingOption.SMALLER)
        assertEquals(6, prefs.view(Prefs.TAB_BROWSE).columns)
        assertEquals(6, (list.recycler().layoutManager as GridLayoutManager).spanCount)
        assertFalse(list.viewRing.button(RingOption.SMALLER)!!.isEnabled)
        // At its end: the size alone.
        assertEquals("Smaller\n6 columns", list.labelOf(RingOption.SMALLER))
        assertEquals("Larger\n6 columns→5 columns", list.labelOf(RingOption.LARGER))
        list.tap(RingOption.LARGER)
        assertEquals(5, prefs.view(Prefs.TAB_BROWSE).columns)
        assertTrue(list.viewRing.button(RingOption.SMALLER)!!.isEnabled)
    }

    @Test
    fun favoritesToggleSections() {
        prefs.ringOptions = RingOptions.DEFAULT
        val list = launch(tab = 1)
        list.openRing()
        assertEquals("Sections\nOn→Off", list.labelOf(RingOption.SECTIONS))
        list.tap(RingOption.SECTIONS)
        assertFalse(prefs.groupFavorites)
        assertEquals("Sections\nOff→On", list.labelOf(RingOption.SECTIONS))
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

    // ------------------------------------------------------------------ chosen in Settings

    private fun ListActivity.ringShows(): List<RingOption> = RingOption.entries.filter { viewRing.button(it) != null }

    @Test
    fun byDefaultTheRingLeavesLayoutToTheButton() {
        prefs.ringOptions = RingOptions.DEFAULT
        val list = launch()
        list.openRing()
        assertEquals(listOf(RingOption.SMALLER, RingOption.LARGER, RingOption.DETAIL, RingOption.SORT, RingOption.FOLDERS_FIRST), list.ringShows())
        list.viewRing.close(animate = false)
        val history = launch(tab = 2)
        history.openRing()
        assertEquals(listOf(RingOption.SMALLER, RingOption.LARGER, RingOption.DETAIL, RingOption.SORT), history.ringShows())
    }

    @Test
    fun theRingShowsTheThreeToSixChosenInTheirOrder() {
        prefs.ringOptions = RingOptions.DEFAULT.moved(3, 0).with(RingOption.LARGER, false).with(RingOption.FOLDERS_FIRST, false)
        val list = launch()
        list.openRing()
        assertEquals(setOf(RingOption.SORT, RingOption.SMALLER, RingOption.DETAIL), list.ringShows().toSet())
        // In order from the top of the arc inward: the first highest.
        val tops = listOf(RingOption.SORT, RingOption.SMALLER, RingOption.DETAIL).map { center(list.viewRing.button(it)!!).second }
        assertEquals(tops.sorted(), tops)
        list.viewRing.close(animate = false)

        prefs.ringOptions = RingOptions.DEFAULT.with(RingOption.DETAIL, false).with(RingOption.FOLDERS_FIRST, false)
            .with(RingOption.LAYOUT, true).with(RingOption.TITLE_LINES, true)
        val favorites = launch(tab = 1)
        favorites.openRing()
        assertEquals(6, favorites.ringShows().size)
        assertEquals("Title lines\nDouble line→Bounce", favorites.labelOf(RingOption.TITLE_LINES))
        favorites.tap(RingOption.TITLE_LINES)
        assertEquals(TitleDisplay.BOUNCE, prefs.titleDisplay)
        assertEquals("Title lines\nBounce→Marquee", favorites.labelOf(RingOption.TITLE_LINES))
        favorites.assertLabelsClear(Prefs.TAB_FAVORITES)
    }

    @Test
    fun theButtonsTapRunsTheChosenActionAndItsIconSaysSo() {
        prefs.fabTap = RingOption.SMALLER
        prefs.setGridSize(Prefs.TAB_BROWSE, 5, 0)
        val list = launch()
        val fab = list.fab()
        assertTrue(fab.contentDescription.toString().startsWith("Smaller: 5 columns, tap for 6 columns"))
        assertEquals(R.drawable.ic_zoom_out, shadowOf(fab.drawable).createdFromResId)
        assertEquals(255, fab.imageAlpha)
        assertNull(ViewCompat.getStateDescription(fab))
        fab.performClick()
        settle()
        assertEquals(6, prefs.view(Prefs.TAB_BROWSE).columns)
        assertTrue(prefs.view(Prefs.TAB_BROWSE).grid)
        // At its end: grayed, TalkBack told so, and a tap changes nothing.
        assertEquals(R.drawable.ic_zoom_out, shadowOf(fab.drawable).createdFromResId)
        assertTrue(fab.imageAlpha < 128)
        assertEquals("At its end", ViewCompat.getStateDescription(fab).toString())
        fab.performClick()
        settle()
        assertEquals(6, prefs.view(Prefs.TAB_BROWSE).columns)
        // Back to the layout toggle: its own icon, no state.
        prefs.fabTap = RingOption.LAYOUT
        val again = launch()
        assertEquals(R.drawable.ic_view_list, shadowOf(again.fab().drawable).createdFromResId)
        assertNull(ViewCompat.getStateDescription(again.fab()))
        // The long press still opens the ring.
        list.openRing()
    }

    @Test
    fun aTabOnlyTapActionTogglesTheLayoutElsewhere() {
        prefs.fabTap = RingOption.FOLDERS_FIRST
        val list = launch()
        list.fab().performClick()
        settle()
        assertFalse(prefs.sort(Prefs.TAB_BROWSE).foldersFirst)
        assertTrue(prefs.view(Prefs.TAB_BROWSE).grid)
        val history = launch(tab = 2)
        assertEquals(history.getString(R.string.menu_grid), history.fab().contentDescription.toString())
        history.fab().performClick()
        settle()
        assertTrue(prefs.view(Prefs.TAB_HISTORY).grid)
    }

    /** The open ring's radius against the first ring's, in dp, and its buttons' spacing. */
    private fun ListActivity.assertSmallerRing(tab: String) {
        val density = resources.displayMetrics.density
        val options = ViewRing.options(tab, prefs.ringOptions)
        val first = RingGeometry.radius(options.size, 112f, 136f)
        val radius = viewRing.ringRadius / density
        assertTrue("radius $radius of $first", radius <= first * 0.7f)
        val centers = options.map { center(viewRing.button(it)!!) }
        for ((a, b) in centers.zipWithNext()) assertTrue("buttons ${hypot(a.first - b.first, a.second - b.second) / density} dp apart", hypot(a.first - b.first, a.second - b.second) >= 56 * density)
        for (o in options) assertTrue("$o reachable", viewRing.button(o)!!.width >= 48 * density)
        assertLabelsClear(tab)
    }

    @Test
    fun theRingIsTwoThirdsTheFirstAtFullWidth() {
        for (left in listOf(false, true)) {
            prefs.leftHanded = left
            val list = launch()
            settle()
            list.openRing()
            val n = ViewRing.options(Prefs.TAB_BROWSE, prefs.ringOptions).size
            assertEquals(RingGeometry.radius(n, 75f, 91f), list.viewRing.ringRadius / list.resources.displayMetrics.density, 1f)
            list.assertSmallerRing(Prefs.TAB_BROWSE)
            list.viewRing.close(animate = false)
        }
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun theSmallerRingFitsA400dpWindowForBothHands() {
        for (left in listOf(false, true)) {
            prefs.leftHanded = left
            val list = launch()
            settle()
            list.openRing()
            list.assertSmallerRing(Prefs.TAB_BROWSE)
            list.viewRing.close(animate = false)
        }
    }

    @Test
    fun keysReachOnlyTheRingWhileItShows() {
        val list = launch(touch = false)
        val rows = list.recycler()
        val item = rows.getChildAt(rows.childCount - 1)
        list.openRing()
        assertSame(list.viewRing.button(RingOption.LAYOUT), list.window.decorView.findFocus())
        assertFalse(item.requestFocus())
        assertFalse(rows.hasFocus())
        list.viewRing.close(animate = false)
        assertTrue(list.fab().isFocused)
        assertTrue(item.requestFocus())
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

    // ------------------------------------------------------------------ dragging from the long press

    /** One finger on the window, down at [x], [y] until [lift]ed. */
    private class Finger(val list: ListActivity, var x: Float, var y: Float) {
        private val down = SystemClock.uptimeMillis()

        init {
            send(MotionEvent.ACTION_DOWN)
        }

        private fun send(action: Int) {
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            list.window.decorView.dispatchTouchEvent(e)
            e.recycle()
        }

        /** Slides to [tx], [ty] in a few moves. */
        fun moveTo(tx: Float, ty: Float, steps: Int = 6) {
            val fx = x
            val fy = y
            for (i in 1..steps) {
                x = fx + (tx - fx) * i / steps
                y = fy + (ty - fy) * i / steps
                send(MotionEvent.ACTION_MOVE)
            }
        }

        fun lift() = send(MotionEvent.ACTION_UP)

        /** The system takes the finger away. */
        fun cancel() = send(MotionEvent.ACTION_CANCEL)
    }

    private fun Finger.moveTo(v: View) = center(v).let { (cx, cy) -> moveTo(cx, cy) }

    private val longPress get() = ViewConfiguration.getLongPressTimeout().toLong()

    /** Holds a finger on the grid/list button until the ring opens and has fanned out. */
    private fun ListActivity.pressAndHold(): Finger {
        val (x, y) = center(fab())
        val finger = Finger(this, x, y)
        settle(longPress)
        settle(300)
        assertTrue(viewRing.isOpen)
        assertTrue(viewRing.following)
        return finger
    }

    private fun launchHanded(left: Boolean): ListActivity {
        prefs.leftHanded = left
        return launch().also { settle() }
    }

    private fun ListActivity.columnsShown() = (recycler().layoutManager as GridLayoutManager).spanCount

    private fun liftingWhereTheLongPressFiredLeavesTheRingOpenForTaps(left: Boolean) {
        val list = launchHanded(left)
        val finger = list.pressAndHold()
        finger.lift()
        settle()
        assertTrue(list.viewRing.isOpen)
        assertFalse(list.viewRing.following)
        assertFalse(list.fab().isPressed)
        list.tap(RingOption.LAYOUT)
        assertFalse(prefs.view(Prefs.TAB_BROWSE).grid)
        assertTrue(list.viewRing.isOpen)
        // A long press on Sort still opens the Sort popup.
        assertTrue(list.viewRing.button(RingOption.SORT)!!.performLongClick())
        settle()
        assertFalse(list.viewRing.isOpen)
        assertTrue((ShadowDialog.getLatestDialog() as AlertDialog).isShowing)
        assertFalse(prefs.sort(Prefs.TAB_BROWSE).by.flipped)
    }

    @Test
    fun liftingWhereTheLongPressFiredLeavesTheRingOpenForTaps() = liftingWhereTheLongPressFiredLeavesTheRingOpenForTaps(left = false)

    @Test
    fun liftingWhereTheLongPressFiredLeavesTheRingOpenForTapsLeftHanded() = liftingWhereTheLongPressFiredLeavesTheRingOpenForTaps(left = true)

    @Test
    fun aWiggleOnTheButtonStaysATap() {
        val list = launch()
        val finger = list.pressAndHold()
        val r = list.fab().width / 4f
        finger.moveTo(finger.x - r, finger.y - r)
        finger.lift()
        settle()
        assertTrue(list.viewRing.isOpen)
        assertTrue(prefs.view(Prefs.TAB_BROWSE).grid)
    }

    private fun draggingToAButtonAndLiftingRunsItAndCloses(left: Boolean) {
        val list = launchHanded(left)
        val columns = prefs.view(Prefs.TAB_BROWSE).columns
        val finger = list.pressAndHold()
        finger.moveTo(list.viewRing.button(RingOption.LARGER)!!)
        assertTrue(list.viewRing.button(RingOption.LARGER)!!.isPressed)
        finger.moveTo(list.viewRing.button(RingOption.DETAIL)!!)
        assertTrue(list.viewRing.button(RingOption.DETAIL)!!.isPressed)
        assertFalse(list.viewRing.button(RingOption.LARGER)!!.isPressed)
        finger.lift()
        settle()
        assertEquals(DetailDisplay.DENSE, prefs.view(Prefs.TAB_BROWSE).detail)
        // Only the button lifted on ran.
        assertEquals(columns, prefs.view(Prefs.TAB_BROWSE).columns)
        assertFalse(list.viewRing.isOpen)
        assertFalse(list.fab().isPressed)
    }

    @Test
    fun draggingToAButtonAndLiftingRunsItAndCloses() = draggingToAButtonAndLiftingRunsItAndCloses(left = false)

    @Test
    fun draggingToAButtonAndLiftingRunsItAndClosesLeftHanded() = draggingToAButtonAndLiftingRunsItAndCloses(left = true)

    private fun draggingBackToTheHubAndLiftingCancels(left: Boolean) {
        val list = launchHanded(left)
        val finger = list.pressAndHold()
        val (hx, hy) = center(list.fab())
        finger.moveTo(list.viewRing.button(RingOption.LAYOUT)!!)
        assertTrue(list.viewRing.button(RingOption.LAYOUT)!!.isPressed)
        finger.moveTo(hx + 2, hy - 2)
        assertFalse(list.viewRing.button(RingOption.LAYOUT)!!.isPressed)
        finger.lift()
        settle()
        assertTrue(prefs.view(Prefs.TAB_BROWSE).grid)
        assertFalse(list.viewRing.isOpen)
    }

    @Test
    fun draggingBackToTheHubAndLiftingCancels() = draggingBackToTheHubAndLiftingCancels(left = false)

    @Test
    fun draggingBackToTheHubAndLiftingCancelsLeftHanded() = draggingBackToTheHubAndLiftingCancels(left = true)

    private fun farOutsideTheRingPicksByAngle(left: Boolean) {
        val list = launchHanded(left)
        val shown = list.columnsShown()
        val finger = list.pressAndHold()
        val (hx, hy) = center(list.fab())
        val (bx, by) = center(list.viewRing.button(RingOption.LARGER)!!)
        // Three times as far out as the button, along its direction: off the screen.
        finger.moveTo(hx + (bx - hx) * 3, hy + (by - hy) * 3)
        assertTrue(list.viewRing.button(RingOption.LARGER)!!.isPressed)
        finger.lift()
        settle()
        assertFalse(list.viewRing.isOpen)
        assertEquals(shown - 1, prefs.view(Prefs.TAB_BROWSE).columns)
    }

    @Test
    fun farOutsideTheRingPicksByAngle() = farOutsideTheRingPicksByAngle(left = false)

    @Test
    fun farOutsideTheRingPicksByAngleLeftHanded() = farOutsideTheRingPicksByAngle(left = true)

    @Test
    fun aGrayedButtonIsNeitherLitNorRun() {
        prefs.setGridSize(Prefs.TAB_BROWSE, 6, 0)
        val list = launch()
        val finger = list.pressAndHold()
        assertFalse(list.viewRing.button(RingOption.SMALLER)!!.isEnabled)
        finger.moveTo(list.viewRing.button(RingOption.SMALLER)!!)
        assertFalse(list.viewRing.button(RingOption.SMALLER)!!.isPressed)
        finger.lift()
        settle()
        assertEquals(6, prefs.view(Prefs.TAB_BROWSE).columns)
        assertFalse(list.viewRing.isOpen)
    }

    @Test
    fun liftingOnSortFlipsItsDirection() {
        val list = launch()
        val finger = list.pressAndHold()
        finger.moveTo(list.viewRing.button(RingOption.SORT)!!)
        finger.lift()
        settle()
        assertTrue(prefs.sort(Prefs.TAB_BROWSE).by.flipped)
        assertFalse(list.viewRing.isOpen)
        assertNull(ShadowDialog.getLatestDialog())
    }

    @Test
    fun lingeringOnSortOpensTheSortPopup() {
        val list = launch()
        val finger = list.pressAndHold()
        finger.moveTo(list.viewRing.button(RingOption.SORT)!!)
        settle(longPress)
        assertFalse(list.viewRing.isOpen)
        assertTrue((ShadowDialog.getLatestDialog() as AlertDialog).isShowing)
        // Moving on and lifting afterward change nothing.
        finger.moveTo(finger.x - 50, finger.y)
        finger.lift()
        settle()
        assertFalse(prefs.sort(Prefs.TAB_BROWSE).by.flipped)
    }

    @Test
    fun passingOverSortWithoutLingeringOpensNothing() {
        val list = launch()
        val finger = list.pressAndHold()
        finger.moveTo(list.viewRing.button(RingOption.SORT)!!)
        // Less than the linger, with no idling that runs animations to their end.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(longPress / 2))
        assertTrue(list.viewRing.isOpen)
        finger.moveTo(list.viewRing.button(RingOption.DETAIL)!!)
        settle(longPress)
        assertTrue(list.viewRing.isOpen)
        assertNull(ShadowDialog.getLatestDialog())
        finger.lift()
        settle()
        assertEquals(DetailDisplay.DENSE, prefs.view(Prefs.TAB_BROWSE).detail)
        assertFalse(prefs.sort(Prefs.TAB_BROWSE).by.flipped)
    }

    @Test
    fun movingOnWithinSortKeepsItFromLingering() {
        val list = launch()
        val finger = list.pressAndHold()
        val (hx, hy) = center(list.fab())
        val (sx, sy) = center(list.viewRing.button(RingOption.SORT)!!)
        finger.moveTo(sx, sy)
        val slop = ViewConfiguration.get(list).scaledTouchSlop
        val step = 2f * slop / hypot(sx - hx, sy - hy)
        // Outward along Sort's direction, past the slop every tenth of a second, for twice the linger.
        for (i in 1..(2 * longPress / 100).toInt()) {
            finger.moveTo(hx + (sx - hx) * (1 + i * step), hy + (sy - hy) * (1 + i * step), steps = 1)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        }
        assertTrue(list.viewRing.isOpen)
        assertNull(ShadowDialog.getLatestDialog())
        // Within the slop, it lingers.
        finger.moveTo(finger.x + slop / 2f, finger.y, steps = 1)
        settle(longPress)
        assertFalse(list.viewRing.isOpen)
        assertTrue((ShadowDialog.getLatestDialog() as AlertDialog).isShowing)
    }

    @Test
    fun aCancelWhileDraggingClosesWithoutRunning() {
        val list = launch()
        val finger = list.pressAndHold()
        finger.moveTo(list.viewRing.button(RingOption.LAYOUT)!!)
        finger.cancel()
        settle()
        assertFalse(list.viewRing.isOpen)
        assertTrue(prefs.view(Prefs.TAB_BROWSE).grid)
        // Before any drag, a cancel leaves the ring open for taps.
        settle(300)
        val again = list.pressAndHold()
        again.cancel()
        settle()
        assertTrue(list.viewRing.isOpen)
        assertFalse(list.viewRing.following)
        assertTrue(prefs.view(Prefs.TAB_BROWSE).grid)
    }

    @Test
    fun stoppingMidDragOnSortOpensNoPopupLater() {
        val list = launch()
        val finger = list.pressAndHold()
        finger.moveTo(list.viewRing.button(RingOption.SORT)!!)
        controller.pause().stop()
        assertFalse(list.viewRing.isOpen)
        settle(longPress)
        assertNull(ShadowDialog.getLatestDialog())
        finger.lift()
        settle()
        assertFalse(prefs.sort(Prefs.TAB_BROWSE).by.flipped)
        assertTrue(prefs.view(Prefs.TAB_BROWSE).grid)
    }

    @Test
    fun layingTheRingOutAgainLetsGoOfTheFinger() {
        val list = launch()
        val finger = list.pressAndHold()
        finger.moveTo(list.viewRing.button(RingOption.LAYOUT)!!)
        val others = ViewRing.options(Prefs.TAB_FAVORITES).map { ViewRingOverlay.Item(it, R.drawable.ic_sort, "Name", listOf(RingValue("Value", null))) }
        list.viewRing.refresh(others)
        assertFalse(list.viewRing.following)
        for (o in ViewRing.options(Prefs.TAB_FAVORITES)) assertFalse("$o", list.viewRing.button(o)!!.isPressed)
        finger.moveTo(list.viewRing.button(RingOption.SECTIONS)!!)
        finger.lift()
        settle()
        // Neither the ring nor the grid/list button took the lift.
        assertTrue(list.viewRing.isOpen)
        assertTrue(prefs.view(Prefs.TAB_BROWSE).grid)
        assertTrue(prefs.groupFavorites)
    }

    @Test
    fun liftingOnTheHubAfterADragIsNoTapOnTheButton() {
        val list = launch()
        val finger = list.pressAndHold()
        val (hx, hy) = center(list.fab())
        finger.moveTo(list.viewRing.button(RingOption.DETAIL)!!)
        finger.moveTo(hx, hy)
        finger.lift()
        settle()
        assertFalse(list.viewRing.isOpen)
        // The grid/list button's own tap would have switched to a list.
        assertTrue(prefs.view(Prefs.TAB_BROWSE).grid)
        assertTrue(list.recycler().layoutManager is GridLayoutManager)
        assertFalse(list.fab().isPressed)
    }

    @Test
    fun lightingAButtonTicksWithinTheSystemSetting() {
        val list = launch()
        val finger = list.pressAndHold()
        val hub = shadowOf(list.viewRing.hub()!!)
        assertEquals(-1, hub.lastHapticFeedbackPerformed())
        finger.moveTo(list.viewRing.button(RingOption.LARGER)!!)
        // Robolectric records only the call without flags, the one the system setting governs.
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, hub.lastHapticFeedbackPerformed())
        finger.moveTo(list.viewRing.button(RingOption.SORT)!!)
        settle(longPress)
        assertEquals(HapticFeedbackConstants.LONG_PRESS, hub.lastHapticFeedbackPerformed())
    }

    /** Where [v] stands once it has flown out of the hub. */
    private fun home(v: View) = center(v).let { (x, y) -> (x - v.translationX) to (y - v.translationY) }

    @Test
    fun aButtonLitWhileFlyingOutLandsAsItGrows() {
        val list = launch()
        // Frames only as the clock moves, so animations show their middle.
        ShadowChoreographer.setPaused(true)
        val looper = shadowOf(Looper.getMainLooper())
        val (x, y) = center(list.fab())
        val finger = Finger(list, x, y)
        var waited = 0L
        while (!list.viewRing.isOpen && waited < 2 * longPress) {
            looper.idleFor(Duration.ofMillis(10))
            waited += 10
        }
        assertTrue(list.viewRing.isOpen)
        val larger = list.viewRing.button(RingOption.LARGER)!!
        assertTrue(hypot(larger.translationX, larger.translationY) > 0f)
        val (tx, ty) = home(larger)
        finger.moveTo(tx, ty, steps = 2)
        assertTrue(larger.isPressed)
        // Landed and grown with the growth, ahead of the fly-out's own end; one frame per idle.
        repeat(9) { looper.idleFor(Duration.ofMillis(16)) }
        assertEquals(0f, larger.translationX, 0.5f)
        assertEquals(0f, larger.translationY, 0.5f)
        assertEquals(1.15f, larger.scaleX, 0.01f)
        finger.lift()
    }
}
