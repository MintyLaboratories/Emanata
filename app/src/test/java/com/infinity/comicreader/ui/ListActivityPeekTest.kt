package com.infinity.comicreader.ui

import android.Manifest
import android.app.Application
import android.os.Environment
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.reader.Fixtures
import com.infinity.comicreader.reader.TestImages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w900dp-h1400dp")
class ListActivityPeekTest {

    private lateinit var book: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("ui", 0).edit().clear().commit()
        val dir = File(Environment.getExternalStorageDirectory(), "attic/Series B").apply { mkdirs() }
        val page = TestImages.png(4, 6)
        book = Fixtures.zip(File(dir, "Vol 1.cbz"), "01.png" to page, "02.png" to page, "03.png" to page)
        File(dir, "Extras").mkdirs()
        Prefs(context).lastPath = dir.absolutePath
    }

    private fun idle(ms: Long = 0) {
        repeat(10) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms / 10 + 20))
            Thread.sleep(20)
        }
    }

    private fun ListActivity.peek(): PeekOverlay {
        val root = findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
        return (0 until root.childCount).map { root.getChildAt(it) }.filterIsInstance<PeekOverlay>().single()
    }

    private fun ListActivity.longPress(file: File) {
        val recycler = findViewById<RecyclerView>(R.id.recycler)
        val position = (recycler.adapter as BookAdapter).positionOf(file.absolutePath)
        recycler.findViewHolderForAdapterPosition(position)!!.itemView.performLongClick()
    }

    private fun AlertDialog.click(label: String) {
        val items = (0 until listView.adapter.count).map { listView.adapter.getItem(it).toString() }
        listView.performItemClick(null, items.indexOf(label), 0)
    }

    @Test
    fun peekFromTheMenuOpensAndBackClosesIt() {
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        activity.longPress(book)
        val menu = ShadowDialog.getLatestDialog() as AlertDialog
        menu.click("Peek")
        idle(500)
        val peek = activity.peek()
        assertTrue(peek.isShowing)
        assertEquals(View.VISIBLE, peek.visibility)
        assertEquals(1f, peek.progress, 0f)

        @Suppress("DEPRECATION")
        activity.onBackPressed()
        idle(500)
        assertFalse(peek.isShowing)
        assertEquals(View.GONE, peek.visibility)
        // Back closed the peek, not the folder.
        assertEquals(book.parentFile!!.absolutePath, Prefs(activity).lastPath)
    }

    /** A touch with a finger at each of [points]. */
    private fun touch(action: Int, vararg points: Pair<Float, Float>): MotionEvent {
        val properties = Array(points.size) { i ->
            MotionEvent.PointerProperties().apply {
                id = i
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(points.size) { i ->
            MotionEvent.PointerCoords().apply {
                x = points[i].first
                y = points[i].second
                pressure = 1f
                size = 1f
            }
        }
        val now = SystemClock.uptimeMillis()
        return MotionEvent.obtain(now, now, action, points.size, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    private val secondDown = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
    private val secondUp = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    /** Puts two fingers 10 px apart on [file]'s tile, then spreads them to [spread] px apart and lifts one. */
    private fun ListActivity.spread(file: File, spread: Float?) {
        val recycler = findViewById<RecyclerView>(R.id.recycler)
        val position = (recycler.adapter as BookAdapter).positionOf(file.absolutePath)
        val tile = recycler.findViewHolderForAdapterPosition(position)!!.itemView
        val x = tile.left + tile.width / 2f
        val y = tile.top + tile.height / 2f
        recycler.dispatchTouchEvent(touch(MotionEvent.ACTION_DOWN, x to y))
        idle(50)
        recycler.dispatchTouchEvent(touch(secondDown, x to y, x + 10 to y))
        // Past the long-press timeout: the tile no longer has the touch.
        idle(1000)
        var end = x + 10
        if (spread != null) {
            for (step in 1..4) {
                end = x + 10 + (spread - 10) * step / 4
                recycler.dispatchTouchEvent(touch(MotionEvent.ACTION_MOVE, x to y, end to y))
            }
        }
        recycler.dispatchTouchEvent(touch(secondUp, x to y, end to y))
        recycler.dispatchTouchEvent(touch(MotionEvent.ACTION_UP, x to y))
        idle(500)
    }

    @Test
    fun spreadingPartWayKeepsAPeekOpenWithoutALongPress() {
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        activity.spread(book, spread = 130f)
        assertNull(ShadowDialog.getLatestDialog())
        assertTrue(activity.peek().isShowing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun spreadingFullyOpensTheBook() {
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        activity.spread(book, spread = 2000f)
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(ViewerActivity::class.java.name, started?.component?.className)
        assertEquals(book.absolutePath, started?.getStringExtra(ViewerActivity.EXTRA_PATH))
        assertFalse(activity.peek().isShowing)
    }

    @Test
    fun twoFingersThatDoNotSpreadDoNothing() {
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        activity.spread(book, spread = null)
        assertNull(ShadowDialog.getLatestDialog())
        assertFalse(activity.peek().isShowing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun foldersOfferNoPeek() {
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        activity.longPress(File(book.parentFile, "Extras"))
        val menu = ShadowDialog.getLatestDialog() as AlertDialog
        val items = (0 until menu.listView.adapter.count).map { menu.listView.adapter.getItem(it).toString() }
        assertFalse("Peek" in items)
    }
}
