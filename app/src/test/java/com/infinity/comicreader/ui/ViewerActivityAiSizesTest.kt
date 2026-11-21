package com.infinity.comicreader.ui

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager2.widget.ViewPager2
import com.infinity.comicreader.R
import com.infinity.comicreader.ai.AiThresholds
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ResumeMode
import com.infinity.comicreader.reader.Fixtures
import com.infinity.comicreader.testing.idleUntil
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.File

/** The reader back from Settings: pages shown go through the models their sizes call for under the stored handles. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerActivityAiSizesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        prefs = Prefs(context)
        prefs.resumeMode = ResumeMode.NO
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun open(): ActivityController<ViewerActivity> {
        val pages = (0 until 3).map { Fixtures.entry("p%02d.png".format(it), byteArrayOf(0)) }
        val book = Fixtures.zip(File(tmp.newFolder(), "Sample v01.cbz"), *pages.toTypedArray())
        val intent = Intent(context, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_PATH, book.absolutePath)
        val controller = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup()
        val progress = controller.get().findViewById<View>(R.id.viewer_progress)
        idleUntil("the book open") { progress.visibility != View.VISIBLE }
        return controller
    }

    /** Counts the adapter's whole-range rebinds: [PageAdapter.aiChanged] rebinds every item. */
    private class Rebinds(private val adapter: RecyclerView.Adapter<*>) : RecyclerView.AdapterDataObserver() {
        var count = 0
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
            if (positionStart == 0 && itemCount == adapter.itemCount) count++
        }
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = onItemRangeChanged(positionStart, itemCount, null)
    }

    @Test
    fun aHandleMovedWhileAwayProcessesTheShownPagesAnew() {
        val controller = open()
        val adapter = controller.get().findViewById<ViewPager2>(R.id.viewer_pager).adapter!!
        val rebinds = Rebinds(adapter).also { adapter.registerAdapterDataObserver(it) }

        // Away and back with the handles where they were: nothing to do again.
        controller.pause().stop()
        controller.start().resume()
        idle()
        assertEquals(0, rebinds.count)

        controller.pause().stop()
        prefs.aiThresholds = AiThresholds(upscalePct = 60)
        controller.start().resume()
        idle()
        assertEquals(1, rebinds.count)

        // Moved back: the pages go through the models the default handles call for again.
        controller.pause().stop()
        prefs.aiThresholds = AiThresholds()
        controller.start().resume()
        idle()
        assertEquals(2, rebinds.count)
        controller.pause().stop().destroy()
    }
}
