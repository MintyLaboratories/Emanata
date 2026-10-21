package com.infinity.comicreader.ui

import android.Manifest
import android.app.Application
import android.os.Environment
import android.os.Looper
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ListActivityCrumbsTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("ui", 0).edit().clear().commit()
        root = Environment.getExternalStorageDirectory()
        File(root, "attic/Comics").mkdirs()
        Prefs(context).lastPath = File(root, "attic/Comics").absolutePath
    }

    private fun idle() {
        // The startup location is worked out on the IO pool; let it land on the main thread.
        repeat(10) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    private fun CrumbBar.row(): LinearLayout = (getChildAt(1) as HorizontalScrollView).getChildAt(0) as LinearLayout

    @Test
    fun crumbsNameTheRootAndNavigate() {
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        val bar = activity.findViewById<CrumbBar>(R.id.pathbar)
        assertEquals(listOf("Device", "attic", "Comics"), bar.crumbs.map { it.label })

        // Separator, "attic", separator, "Comics".
        val parent = bar.row().getChildAt(1) as TextView
        assertEquals("attic", parent.text)
        parent.performClick()
        idle()
        // The folder left stays as a forward crumb.
        assertEquals(listOf("Device", "attic", "Comics"), bar.crumbs.map { it.label })
        assertEquals(1, bar.currentIndex)

        (bar.getChildAt(0) as TextView).performClick()
        idle()
        assertEquals(root.absolutePath, bar.crumbs[bar.currentIndex].path)
        assertEquals(0, bar.currentIndex)
    }

    @Test
    fun forwardCrumbsReopenAndDropOnAnotherFolder() {
        File(root, "attic/Other").mkdirs()
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        val bar = activity.findViewById<CrumbBar>(R.id.pathbar)
        (bar.getChildAt(0) as TextView).performClick()
        idle()
        assertEquals(listOf("Device", "attic", "Comics"), bar.crumbs.map { it.label })

        // Separator, "attic", separator, gray "Comics".
        (bar.row().getChildAt(3) as TextView).performClick()
        idle()
        assertEquals(2, bar.currentIndex)

        (bar.row().getChildAt(1) as TextView).performClick()
        idle()
        assertEquals(1, bar.currentIndex)
        // Opening a folder off the trail drops it.
        val crumbClick = bar.onCrumbClick!!
        crumbClick(com.infinity.comicreader.data.Crumb("Other", File(root, "attic/Other").absolutePath, false))
        idle()
        assertEquals(listOf("Device", "attic", "Other"), bar.crumbs.map { it.label })
        assertEquals(2, bar.currentIndex)
    }

    @Test
    fun backStopsAtTheRoot() {
        Prefs(ApplicationProvider.getApplicationContext()).lastPath = root.absolutePath
        val controller = Robolectric.buildActivity(ListActivity::class.java).setup()
        idle()
        @Suppress("DEPRECATION")
        controller.get().onBackPressed()
        idle()
        assertTrue(controller.get().isFinishing)
        assertEquals(listOf("Device"), controller.get().findViewById<CrumbBar>(R.id.pathbar).crumbs.map { it.label })
    }
}
