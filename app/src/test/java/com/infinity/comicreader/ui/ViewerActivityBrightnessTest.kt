package com.infinity.comicreader.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager2.widget.ViewPager2
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ResumeMode
import com.infinity.comicreader.reader.Fixtures
import org.junit.Assert.assertEquals
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
import java.time.Duration

/** The brightness drag's indicator pill. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerActivityBrightnessTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        Prefs(context).resumeMode = ResumeMode.NO
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun launch(): ViewerActivity {
        val pages = (0 until 3).map { Fixtures.entry("p%02d.png".format(it), byteArrayOf(0)) }
        val book = Fixtures.zip(File(tmp.newFolder("shelf"), "Book A.cbz"), *pages.toTypedArray())
        val intent = Intent(context, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_PATH, book.absolutePath)
        val viewer = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup().get()
        val progress = viewer.findViewById<View>(R.id.viewer_progress)
        val deadline = System.currentTimeMillis() + 5_000
        do {
            Thread.sleep(10)
            idle()
        } while (progress.visibility == View.VISIBLE && System.currentTimeMillis() < deadline)
        return viewer
    }

    private fun descendants(v: View): List<View> =
        listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()

    /** The brightness drag of the page on screen. */
    private fun ViewerActivity.drag(): ZoomImageView.VerticalDrag {
        val pager = findViewById<ViewPager2>(R.id.viewer_pager)
        return descendants(pager).filterIsInstance<ZoomImageView>().firstNotNullOf { it.verticalDrag }
    }

    private fun ViewerActivity.pill() = findViewById<View>(R.id.brightness_pill)

    private fun ViewerActivity.pillText() = findViewById<TextView>(R.id.brightness_text).text.toString()

    @Test
    fun thePillShowsTheSignedValueWhileDraggingAndFadesAfter() {
        val viewer = launch()
        val drag = viewer.drag()
        val h = viewer.resources.displayMetrics.heightPixels.toFloat()
        drag.start()
        assertEquals(View.VISIBLE, viewer.pill().visibility)
        assertEquals("Brightness 0", viewer.pillText())

        // Up is brighter: a tenth of the screen height is 20 of the 200 steps.
        drag.move(-h / 10)
        assertEquals("Brightness +20", viewer.pillText())
        drag.move(h / 10 + h * 0.15f + h / 20)
        assertEquals("Brightness −10", viewer.pillText())
        assertEquals(-10, viewer.findViewById<SignedBar>(R.id.brightness_bar).value)

        drag.end()
        // Stays a moment after the finger lifts, then fades out.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals(View.VISIBLE, viewer.pill().visibility)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))
        assertEquals(View.GONE, viewer.pill().visibility)
    }

    @Test
    fun crossingZeroHoldsThereAndSaysSo() {
        val viewer = launch()
        val drag = viewer.drag()
        val h = viewer.resources.displayMetrics.heightPixels.toFloat()
        drag.start()
        drag.move(-h / 10)
        // Back past 0, into the hold.
        drag.move(h / 10 + h * 0.05f)
        assertEquals("Brightness 0", viewer.pillText())
        drag.end()
    }
}
