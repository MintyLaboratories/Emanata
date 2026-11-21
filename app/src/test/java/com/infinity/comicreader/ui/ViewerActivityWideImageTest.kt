package com.infinity.comicreader.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager2.widget.ViewPager2
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ResumeMode
import com.infinity.comicreader.data.WidePages
import com.infinity.comicreader.reader.Fixtures
import com.infinity.comicreader.reader.TestImages
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

/**
 * "Rotate image", the default for wide pages: in portrait a wide page is drawn turned to fill
 * the screen, which is locked while it shows and handed back after; swipes and tap zones stay
 * with the device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerActivityWideImageTest {

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
        RuntimeEnvironment.setQualifiers("port")
    }

    @After
    fun tearDown() = RuntimeEnvironment.setQualifiers("port")

    /** Six pages, the third wide. */
    private fun book(): File =
        Fixtures.zip(
            File(dir, "Tale 001.cbz"),
            *(1..6).map { Fixtures.entry("Tale 001-%03d.png".format(it), if (it == 3) wide else portrait) }.toTypedArray()
        )

    /** The pages of [book] in a solid RAR, whose sizes are learned as pages load. */
    private fun solidBook(): File =
        File(dir, "Tale 002.cbr").apply {
            writeBytes(Fixtures.rar4Bytes(*(1..6).map { Fixtures.entry("Tale 002-%03d.png".format(it), if (it == 3) wide else portrait) }.toTypedArray(), solid = true))
        }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun idleFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        do {
            Thread.sleep(10)
            idle()
        } while (!condition() && System.currentTimeMillis() < deadline)
    }

    private fun intent() = Intent(context, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_PATH, book().absolutePath)

    private fun launch(): ViewerActivity {
        val viewer = Robolectric.buildActivity(ViewerActivity::class.java, intent()).setup().get()
        ready(viewer)
        return viewer
    }

    /** Waits for [viewer] to show the book, with its page sizes read and stored. */
    private fun ready(viewer: ViewerActivity) {
        val progress = viewer.findViewById<View>(R.id.viewer_progress)
        waitUntil { progress.visibility != View.VISIBLE }
        waitUntil { File(context.cacheDir, "page-sizes-by-id").listFiles()?.any { it.name.endsWith(".bin") } == true }
        repeat(5) { Thread.sleep(10); idle() }
    }

    private fun ViewerActivity.pager() = findViewById<ViewPager2>(R.id.viewer_pager)

    private fun ViewerActivity.indicator() = findViewById<TextView>(R.id.viewer_page_text).text.toString()

    private fun ViewerActivity.next() {
        findViewById<View>(R.id.btn_next_page).performClick()
        idle()
    }

    private fun ViewerActivity.previous() {
        findViewById<View>(R.id.btn_prev_page).performClick()
        idle()
    }

    /** The holder of item [position], once bound. */
    private fun ViewerActivity.holder(position: Int = pager().currentItem): PageAdapter.PageHolder? =
        (pager().getChildAt(0) as RecyclerView).findViewHolderForAdapterPosition(position) as? PageAdapter.PageHolder

    /** Quarter turns item [position] shows at, by its frame, checked against its image. */
    private fun ViewerActivity.turnOf(position: Int = pager().currentItem): Int {
        val holder = holder(position)!!
        val turn = holder.turner!!.turn
        assertEquals(turn, holder.images[0].turn)
        return turn
    }

    /** Turns the screen to [qualifier] (`land` or `port`), as the system does after a request. */
    private fun ViewerActivity.turn(qualifier: String) {
        RuntimeEnvironment.setQualifiers(qualifier)
        onConfigurationChanged(resources.configuration)
        idleFor(100)
    }

    @Test
    fun aWidePageIsDrawnTurnedWithTheScreenLockedUntilTheNextPage() {
        val viewer = launch()
        viewer.next()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, viewer.requestedOrientation)
        assertEquals(0, viewer.turnOf())
        viewer.next()
        assertEquals("3 / 6", viewer.indicator())
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LOCKED, viewer.requestedOrientation)
        assertEquals(1, viewer.turnOf())
        viewer.next()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, viewer.requestedOrientation)
        assertEquals(0, viewer.turnOf())
    }

    @Test
    fun theTurnedPageIsLaidOutAcrossTheScreen() {
        val viewer = launch()
        repeat(2) { viewer.next() }
        idleFor(100)
        val holder = viewer.holder()!!
        val frame = holder.turner!!
        assertEquals(frame.height, holder.container.width)
        assertEquals(frame.width, holder.container.height)
        assertEquals(90f, holder.container.rotation, 0f)
    }

    @Test
    fun leftHandedTheTurnGoesTheOtherWay() {
        prefs.leftHanded = true
        val viewer = launch()
        repeat(2) { viewer.next() }
        assertEquals(-1, viewer.turnOf())
    }

    @Test
    fun aWidePageSlidesInAlreadyTurned() {
        val viewer = launch()
        viewer.next()
        val pager = viewer.pager()
        val forward = if (pager.layoutDirection == View.LAYOUT_DIRECTION_RTL) 1f else -1f
        pager.beginFakeDrag()
        pager.fakeDragBy(forward * pager.width / 10f)
        idle()
        // Still on page 2, the wide page beside it is turned already.
        assertEquals(1, pager.currentItem)
        assertEquals(1, viewer.turnOf(2))
        pager.endFakeDrag()
        repeat(100) { if (pager.scrollState != ViewPager2.SCROLL_STATE_IDLE) idleFor(20) }
    }

    @Test
    fun withAnotherChoiceNothingIsTurned() {
        prefs.widePages = WidePages.NOTHING
        val viewer = launch()
        repeat(2) { viewer.next() }
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, viewer.requestedOrientation)
        assertEquals(0, viewer.turnOf())
        prefs.widePages = WidePages.ORIENTATION
        idle()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, viewer.requestedOrientation)
        assertEquals(0, viewer.turnOf())
    }

    @Test
    fun changingOrientationPickedWhileLockedTurnsTheScreenInstead() {
        val viewer = launch()
        repeat(2) { viewer.next() }
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LOCKED, viewer.requestedOrientation)
        ReaderSheet(viewer).tap(R.id.btn_wide_orientation).dialog.dismiss()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, viewer.requestedOrientation)
        assertEquals(0, viewer.turnOf())
    }

    @Test
    fun inLandscapeNothingTurnsOrLocks() {
        RuntimeEnvironment.setQualifiers("land")
        val viewer = launch()
        repeat(2) { viewer.next() }
        assertEquals("3 / 6", viewer.indicator())
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, viewer.requestedOrientation)
        assertEquals(0, viewer.turnOf())
    }

    @Test
    fun aTurnToLandscapeLetsTheLockGoAndBackToPortraitLocksAgain() {
        val viewer = launch()
        repeat(2) { viewer.next() }
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LOCKED, viewer.requestedOrientation)
        // As in split screen, where the window's shape changes whatever the lock.
        viewer.turn("land")
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, viewer.requestedOrientation)
        assertEquals(0, viewer.turnOf())
        viewer.turn("port")
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LOCKED, viewer.requestedOrientation)
        assertEquals(1, viewer.turnOf())
    }

    @Test
    fun inSplitScreenNothingTurnsOrLocks() {
        val viewer = launch()
        repeat(2) { viewer.next() }
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LOCKED, viewer.requestedOrientation)
        // A portrait half of the screen, where the system ignores a lock.
        shadowOf(viewer).setInMultiWindowMode(true)
        viewer.onMultiWindowModeChanged(true, viewer.resources.configuration)
        idleFor(100)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, viewer.requestedOrientation)
        assertEquals(0, viewer.turnOf())
        viewer.next()
        viewer.previous()
        assertEquals("3 / 6", viewer.indicator())
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, viewer.requestedOrientation)
        assertEquals(0, viewer.turnOf())
        // The whole screen again: the page turns, and the screen locks.
        shadowOf(viewer).setInMultiWindowMode(false)
        viewer.onMultiWindowModeChanged(false, viewer.resources.configuration)
        idleFor(100)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LOCKED, viewer.requestedOrientation)
        assertEquals(1, viewer.turnOf())
    }

    @Test
    fun aWindowChangeDuringASwipeLetsTheLockGoOnceItEnds() {
        val viewer = launch()
        repeat(2) { viewer.next() }
        val pager = viewer.pager()
        val forward = if (pager.layoutDirection == View.LAYOUT_DIRECTION_RTL) 1f else -1f
        pager.beginFakeDrag()
        pager.fakeDragBy(forward * pager.width / 10f)
        idle()
        shadowOf(viewer).setInMultiWindowMode(true)
        viewer.onConfigurationChanged(viewer.resources.configuration)
        idle()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LOCKED, viewer.requestedOrientation)
        // Back on the page it left, the lock goes.
        pager.endFakeDrag()
        repeat(100) { if (pager.scrollState != ViewPager2.SCROLL_STATE_IDLE) idleFor(20) }
        idleFor(100)
        assertEquals("3 / 6", viewer.indicator())
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, viewer.requestedOrientation)
        assertEquals(0, viewer.turnOf())
    }

    @Test
    fun aLockedReaderStoppedAndStartedStaysLockedAndLetsGoAfter() {
        val controller = Robolectric.buildActivity(ViewerActivity::class.java, intent()).setup()
        val viewer = controller.get()
        ready(viewer)
        repeat(2) { viewer.next() }
        controller.pause().stop()
        controller.restart().start().resume()
        idle()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LOCKED, viewer.requestedOrientation)
        viewer.next()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, viewer.requestedOrientation)
    }

    @Test
    fun turningTheSettingOffWhileLockedLetsGoAndUnturns() {
        val viewer = launch()
        repeat(2) { viewer.next() }
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LOCKED, viewer.requestedOrientation)
        prefs.widePages = WidePages.NOTHING
        idleFor(100)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, viewer.requestedOrientation)
        assertEquals(0, viewer.turnOf())
    }

    @Test
    fun anOrientationPickedWhileLockedWinsAlsoInARecreatedReader() {
        val controller = Robolectric.buildActivity(ViewerActivity::class.java, intent()).setup()
        ready(controller.get())
        repeat(2) { controller.get().next() }
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LOCKED, controller.get().requestedOrientation)
        ReaderSheet(controller.get()).tap(R.id.btn_orient_portrait).dialog.dismiss()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, controller.get().requestedOrientation)
        // The picture stays turned: only the lock gave way.
        assertEquals(1, controller.get().turnOf())
        controller.recreate()
        val viewer = controller.get()
        ready(viewer)
        assertEquals("3 / 6", viewer.indicator())
        assertTrue(viewer.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_LOCKED)
    }

    @Test
    fun aPageFoundWideAfterItShowsTurnsWithTheLock() {
        val intent = Intent(context, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_PATH, solidBook().absolutePath).putExtra(ViewerActivity.EXTRA_PAGE, 2)
        val viewer = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup().get()
        ready(viewer)
        // Sizes learned are laid out after a wait.
        val deadline = System.currentTimeMillis() + 5_000
        while (viewer.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_LOCKED && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            idleFor(50)
        }
        idleFor(100)
        assertEquals("3 / 6", viewer.indicator())
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LOCKED, viewer.requestedOrientation)
        assertEquals(1, viewer.turnOf())
        val holder = viewer.holder()!!
        assertEquals(holder.turner!!.height, holder.container.width)
    }

    /** A tap at ([fx], [fy]), fractions of the pager, along the device's axes. */
    private fun ViewerActivity.tap(fx: Float, fy: Float) {
        val pager = pager()
        val x = fx * pager.width
        val y = fy * pager.height
        val t = SystemClock.uptimeMillis()
        pager.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        pager.dispatchTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0))
        idleFor(500)
    }

    @Test
    fun tapZonesStayWithTheDeviceOnATurnedPage() {
        for (left in listOf(false, true)) {
            prefs.leftHanded = left
            val viewer = launch()
            repeat(2) { viewer.next() }
            assertEquals(if (left) -1 else 1, viewer.turnOf())
            // The device's right third turns on, its left third back, whichever way the picture lies.
            viewer.tap(0.9f, 0.2f)
            assertEquals("left-handed $left", "4 / 6", viewer.indicator())
            viewer.tap(0.1f, 0.2f)
            assertEquals("left-handed $left", "3 / 6", viewer.indicator())
            viewer.tap(0.1f, 0.8f)
            assertEquals("left-handed $left", "2 / 6", viewer.indicator())
        }
    }

    @Test
    fun aSwipeAcrossTheDeviceTurnsTheTurnedPage() {
        val viewer = launch()
        repeat(2) { viewer.next() }
        val pager = viewer.pager()
        val y = pager.height / 2f
        val forward = if (pager.layoutDirection == View.LAYOUT_DIRECTION_RTL) 1f else -1f
        val from = pager.width * 0.7f
        val t = SystemClock.uptimeMillis()
        pager.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, from, y, 0))
        for (i in 1..8) {
            pager.dispatchTouchEvent(MotionEvent.obtain(t, t + 10L * i, MotionEvent.ACTION_MOVE, from + forward * pager.width * 0.06f * i, y, 0))
        }
        pager.dispatchTouchEvent(MotionEvent.obtain(t, t + 90, MotionEvent.ACTION_UP, from + forward * pager.width * 0.48f, y, 0))
        repeat(100) { if (pager.scrollState != ViewPager2.SCROLL_STATE_IDLE) idleFor(20) }
        idle()
        assertEquals("4 / 6", viewer.indicator())
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, viewer.requestedOrientation)
    }
}
