package com.infinity.comicreader.ui

import android.Manifest
import android.app.Application
import android.os.Environment
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.color.MaterialColors
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.files.FileClipboard
import com.infinity.comicreader.files.FileWork
import com.infinity.comicreader.net.Remotes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.util.concurrent.Semaphore

/** The operation stack on the library screen: its side, its bubbles' looks, folding, and canceling from it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ListActivityStackTest {

    private lateinit var context: Application
    private lateinit var shelf: File
    private var controller: ActivityController<ListActivity>? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        for (name in listOf("ui", "library")) context.getSharedPreferences(name, 0).edit().clear().commit()
        shelf = File(Environment.getExternalStorageDirectory(), "stack").apply {
            deleteRecursively()
            mkdirs()
        }
        File(shelf, "one.cbz").writeBytes(ByteArray(10))
        Prefs(context).lastPath = shelf.absolutePath
        Prefs(context).thumbnails = false
        FileClipboard.clear()
        FileWork.reset()
        Remotes.serve(emptyList()) { throw AssertionError("no shares") }
    }

    @After
    fun tearDown() {
        controller?.let { if (!it.get().isDestroyed) it.pause().stop().destroy() }
        FileWork.reset()
        FileClipboard.clear()
    }

    private fun idle() = repeat(10) {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(10)
    }

    private fun launch(): ListActivity =
        Robolectric.buildActivity(ListActivity::class.java).also { controller = it }.setup().get().also { idle() }

    private fun waitFor(what: () -> Boolean) {
        repeat(300) {
            idle()
            if (what()) return
        }
        throw AssertionError("never came: ${FileWork.operations.value}")
    }

    private fun pick(name: String) = FileClipboard.add(FileClipboard.Clip(listOf(FileClipboard.Item(File(shelf, name).absolutePath, false)), move = false))

    @Test
    fun olderBubblesFoldIntoACountOnTheTopOne() {
        val activity = launch()
        for (i in 1..5) pick("$i.cbz")
        idle()
        // Under the button: three bubbles, the newest at the bottom.
        val buttons = activity.stack.buttons()
        assertEquals(3, buttons.size)
        assertEquals(FileClipboard.clips.value.takeLast(3).map { it.id }, buttons.map { it.first.order })
        val folded = (buttons.first().second.parent as View).findViewById<TextView>(R.id.op_folded)
        assertEquals(View.VISIBLE, folded.visibility)
        assertEquals("+2", folded.text.toString())
        assertEquals(View.GONE, (buttons[1].second.parent as View).findViewById<TextView>(R.id.op_folded).visibility)

        // Without the button, four.
        Prefs(context).listQuickFab = false
        idle()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.fab).visibility)
        assertEquals(4, activity.stack.buttons().size)
        assertEquals("+1", (activity.stack.buttons().first().second.parent as View).findViewById<TextView>(R.id.op_folded).text.toString())
    }

    @Test
    fun theRunningBubbleDarkensWithARingWaitingOnesAreGrayAndALongPressCancels() {
        val activity = launch()
        val started = Semaphore(0)
        val go = Semaphore(0)
        FileWork.start(FileWork.Kind.COPY, "a.cbz", count = 2) { p ->
            p.item("a.cbz", 0, 2)
            p.report(5, 10)
            started.release()
            go.acquire()
            FileWork.Outcome.Done(null, count = 2)
        }
        FileWork.start(FileWork.Kind.DELETE, "b.cbz") { throw AssertionError("canceled first") }
        started.acquire()
        idle()
        val (running, waiting) = activity.stack.buttons().map { it.second }
        fun color(view: View, attr: Int) = MaterialColors.getColor(view, attr)
        fun ring(button: FloatingActionButton) = (button.parent as View).findViewById<CircularProgressIndicator>(R.id.op_ring)

        assertEquals(View.VISIBLE, ring(running).visibility)
        assertEquals(250, ring(running).progress)
        assertEquals(color(running, com.google.android.material.R.attr.colorSecondary), ring(running).indicatorColor.single())
        assertNotEquals(color(running, com.google.android.material.R.attr.colorSecondary), running.backgroundTintList!!.defaultColor)
        assertEquals(View.INVISIBLE, ring(waiting).visibility)
        assertEquals(color(waiting, FabLook.WAITING_BACKGROUND), waiting.backgroundTintList!!.defaultColor)

        waiting.performLongClick()
        idle()
        assertEquals(context.getString(R.string.file_canceled), ShadowToast.getTextOfLatestToast())
        assertEquals(1, activity.stack.buttons().size)

        go.release()
        waitFor { FileWork.operations.value.isEmpty() }
        assertEquals(context.resources.getQuantityString(R.plurals.copied_count, 2, 2), ShadowToast.getTextOfLatestToast())
        assertTrue(activity.stack.buttons().isEmpty())
    }
}
