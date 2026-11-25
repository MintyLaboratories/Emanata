package minty.emanata.ui

import android.Manifest
import android.app.Application
import android.content.DialogInterface
import android.content.Intent
import android.os.Environment
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import minty.emanata.R
import minty.emanata.testing.idleUntil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File
import kotlin.random.Random

/** Bit-identical copies show as one cover with every place a copy is. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CleanupActivityTest {

    private lateinit var context: Application
    private lateinit var root: File
    private lateinit var copies: List<File>

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        root = File(Environment.getExternalStorageDirectory(), "stacks").apply { deleteRecursively(); mkdirs() }
        val content = Random(7).nextBytes(4096)
        copies = listOf("first/Tale.cbz", "second/Tale.cbz", "third/Tale again.cbz").map {
            File(root, it).apply { parentFile!!.mkdirs(); writeBytes(content) }
        }
        File(root, "first/Other.cbz").writeBytes(Random(8).nextBytes(4096))
    }

    /** Waits until the search is over: its progress gone and the similar-books bar no longer running. */
    private fun settle(activity: CleanupActivity) {
        val progress = activity.findViewById<View>(R.id.cleanup_progress)
        val bar = activity.findViewById<View>(R.id.similar_bar)
        val stop = activity.findViewById<View>(R.id.similar_stop)
        idleUntil("the search over") { progress.visibility != View.VISIBLE && !(bar.visibility == View.VISIBLE && stop.visibility == View.VISIBLE) }
    }

    /** Confirms the delete asked, and waits until the list has dropped what it deleted: then nothing is left ticked. */
    private fun confirmDelete(activity: CleanupActivity) {
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        val delete = activity.findViewById<MaterialButton>(R.id.cleanup_delete)
        idleUntil("the ticked books deleted") { !delete.isEnabled }
    }

    private fun launch(): Pair<CleanupActivity, RecyclerView> {
        val intent = Intent(context, CleanupActivity::class.java)
            .putExtra(CleanupActivity.EXTRA_MODE, CleanupActivity.MODE_DUPES)
            .putExtra(CleanupActivity.EXTRA_ROOT, root.absolutePath)
        val activity = Robolectric.buildActivity(CleanupActivity::class.java, intent).setup().get()
        settle(activity)
        val list = activity.findViewById<RecyclerView>(R.id.cleanup_list)
        list.measure(0, 0)
        list.layout(0, 0, 1200, 2400)
        return activity to list
    }

    private fun RecyclerView.row(position: Int): View = findViewHolderForAdapterPosition(position)!!.itemView

    /** No work of this test's screens runs on into the next. */
    @After
    fun awaitLeftovers() = awaitLeftoverWork()

    @Test
    fun identicalCopiesShowOneCoverAndEveryPlace() {
        val (_, list) = launch()
        assertEquals(2, list.adapter!!.itemCount)
        assertEquals(context.getString(R.string.dupes_identical), list.row(0).findViewById<TextView>(R.id.title).text.toString())
        val group = list.row(1)
        assertTrue(group.findViewById<View>(R.id.box) != null)
        val places = group.findViewById<LinearLayout>(R.id.locations)
        assertEquals(3, places.childCount)
        val titles = (0 until places.childCount).map { places.getChildAt(it).findViewById<TextView>(R.id.title).text.toString() }
        assertTrue(titles.toString(), titles[0].endsWith("first"))
        // Copies whose names differ show their names.
        assertEquals("Tale again.cbz", places.getChildAt(2).findViewById<TextView>(R.id.subtitle).text.toString())
    }

    @Test
    fun keepOneOfEachDeletesTheOtherCopies() {
        val (activity, list) = launch()
        activity.findViewById<MaterialButton>(R.id.cleanup_select).performClick()
        val delete = activity.findViewById<MaterialButton>(R.id.cleanup_delete)
        assertTrue(delete.isEnabled)
        delete.performClick()
        confirmDelete(activity)
        assertTrue(copies[0].exists())
        assertFalse(copies[1].exists())
        assertFalse(copies[2].exists())
        // Nothing left to compare: the group and its header are gone.
        assertEquals(0, list.adapter!!.itemCount)
    }

    @Test
    fun deletingTheShownCopyKeepsTheGroupOnTheOthers() {
        val (activity, list) = launch()
        list.row(1).findViewById<LinearLayout>(R.id.locations).getChildAt(0).performClick()
        activity.findViewById<MaterialButton>(R.id.cleanup_delete).performClick()
        confirmDelete(activity)
        assertFalse(copies[0].exists())
        list.layout(0, 0, 1200, 2400)
        val places = list.row(1).findViewById<LinearLayout>(R.id.locations)
        assertEquals(2, places.childCount)
        assertTrue(places.getChildAt(0).findViewById<TextView>(R.id.title).text.endsWith("second"))
        assertEquals("Tale.cbz", list.row(1).findViewById<TextView>(R.id.title).text.toString())
    }

    /** A touch with fingers at [xs], all at height [y]. */
    private fun touch(action: Int, y: Float, vararg xs: Float): MotionEvent {
        val properties = Array(xs.size) { i ->
            MotionEvent.PointerProperties().apply {
                id = i
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(xs.size) { i ->
            MotionEvent.PointerCoords().apply {
                x = xs[i]
                this.y = y
                pressure = 1f
                size = 1f
            }
        }
        val now = SystemClock.uptimeMillis()
        return MotionEvent.obtain(now, now, action, xs.size, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    @Test
    fun aSpreadOnTheGroupPeeksIntoTheBook() {
        val (activity, list) = launch()
        val group = list.row(1)
        val x = group.left + group.width / 4f
        val y = group.top + group.height / 2f
        val second = 1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT
        // Two fingers on the group spread part-way, which leaves a peek open.
        list.dispatchTouchEvent(touch(MotionEvent.ACTION_DOWN, y, x))
        list.dispatchTouchEvent(touch(MotionEvent.ACTION_POINTER_DOWN or second, y, x, x + 10))
        for (step in 1..4) list.dispatchTouchEvent(touch(MotionEvent.ACTION_MOVE, y, x, x + 10 + 30f * step))
        list.dispatchTouchEvent(touch(MotionEvent.ACTION_POINTER_UP or second, y, x, x + 130))
        list.dispatchTouchEvent(touch(MotionEvent.ACTION_UP, y, x))
        shadowOf(Looper.getMainLooper()).idle()
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val peek = (0 until content.childCount).map { content.getChildAt(it) }.filterIsInstance<PeekOverlay>().single()
        assertTrue(peek.isShowing)
        @Suppress("DEPRECATION")
        activity.onBackPressed()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(peek.isShowing)
        assertFalse(activity.isFinishing)
    }
}
