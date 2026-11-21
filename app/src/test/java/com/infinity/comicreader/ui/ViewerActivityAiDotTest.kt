package com.infinity.comicreader.ui

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ResumeMode
import com.infinity.comicreader.reader.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

/** The reader's AI indicator follows its setting and the pages' AI work, clear of a rounded corner and above the bottom bar. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerActivityAiDotTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("library", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        Prefs(context).resumeMode = ResumeMode.NO
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun open(): ViewerActivity {
        val pages = (0 until 3).map { Fixtures.entry("p%02d.png".format(it), byteArrayOf(0)) }
        val book = Fixtures.zip(File(tmp.newFolder(), "Sample v01.cbz"), *pages.toTypedArray())
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

    @Test
    fun theDotFollowsItsSettingAndStaysAboveTheBottomBar() {
        val viewer = open()
        val dot = viewer.findViewById<View>(R.id.ai_dot)
        assertEquals("off by default", View.GONE, dot.visibility)
        Prefs(context).aiDot = true
        idle()
        // On, but no model works on a page: laid out, unseen.
        assertEquals(View.INVISIBLE, dot.visibility)
        val bar = viewer.findViewById<View>(R.id.viewer_bottom)
        assertEquals(View.VISIBLE, bar.visibility)
        assertTrue("lifted over the bar", dot.translationY < 0)
        assertTrue("${dot.bottom + dot.translationY} above ${bar.top}", dot.bottom + dot.translationY <= bar.top)
        Prefs(context).aiDot = false
        idle()
        assertEquals(View.GONE, dot.visibility)
    }

    @Test
    fun theDotKeepsClearOfTheScreensRoundedCorner() {
        val viewer = open()
        val dot = viewer.findViewById<View>(R.id.ai_dot)
        val root = viewer.findViewById<View>(R.id.viewer_root)
        val margin = viewer.resources.getDimensionPixelSize(R.dimen.ai_dot_margin)
        Prefs(context).aiDot = true
        idle()
        val at = IntArray(2).also(root::getLocationInWindow)
        val awayRight = root.rootView.width - at[0] - root.width
        /** The gap between the dot and the window's right edge. */
        fun gap() = root.rootView.width - (at[0] + dot.right + dot.translationX.toInt())
        assertEquals("square: only the margin", awayRight + margin, gap())
        viewer.screenCorner = { 200 }
        root.requestLayout()
        idle()
        assertEquals(AiDot.offsets(awayRight, 0, 200, margin).first, gap())
        assertTrue("clear of the curve", gap() >= AiDot.cornerGap(200) + margin)
    }
}
