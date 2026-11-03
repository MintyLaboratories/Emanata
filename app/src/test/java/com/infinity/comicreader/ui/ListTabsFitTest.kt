package com.infinity.comicreader.ui

import android.Manifest
import android.app.Application
import android.os.Environment
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.tabs.TabLayout
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The library's tab labels stay on one line at any font scale: tabs share the width while they fit, and scroll otherwise. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ListTabsFitTest {

    private lateinit var context: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        for (name in listOf("ui", "library")) context.getSharedPreferences(name, 0).edit().clear().commit()
        val dir = File(Environment.getExternalStorageDirectory(), "shelf").apply { mkdirs() }
        Prefs(context).lastPath = dir.absolutePath
    }

    /** The tabs of the library on a screen of [qualifiers] at [fontScale], laid out across the screen's width. */
    private fun tabs(qualifiers: String, fontScale: Float): TabLayout {
        RuntimeEnvironment.setQualifiers(qualifiers)
        RuntimeEnvironment.setFontScale(fontScale)
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val tabs = activity.findViewById<TabLayout>(R.id.tabs)
        val width = activity.resources.displayMetrics.widthPixels
        tabs.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        tabs.layout(0, 0, tabs.measuredWidth, tabs.measuredHeight)
        return tabs
    }

    private fun TabLayout.labels(): List<TextView> = (0 until tabCount).map { i -> textOf(getTabAt(i)!!.view) }

    private fun textOf(group: ViewGroup): TextView =
        (0 until group.childCount).map { group.getChildAt(it) }.filterIsInstance<TextView>().first { it.text.isNotEmpty() }

    private fun assertOneLineEach(tabs: TabLayout, what: String) {
        assertEquals(4, tabs.tabCount)
        for (label in tabs.labels()) {
            val layout = label.layout
            assertEquals("$what: \"${label.text}\" on one line", 1, layout.lineCount)
            assertEquals("$what: \"${label.text}\" whole", 0, layout.getEllipsisCount(0))
        }
    }

    @Test
    fun aPhonesLabelsStayOnOneLineAtEveryFontScale() {
        for (scale in listOf(1.0f, 1.3f, 2.0f)) assertOneLineEach(tabs("w411dp-h914dp-xxhdpi", scale), "phone at $scale")
    }

    @Test
    fun aPhoneAtTheDefaultFontScaleSharesTheWidth() {
        assertEquals(TabLayout.MODE_FIXED, tabs("w411dp-h914dp-xxhdpi", 1.0f).tabMode)
    }

    @Test
    fun aPhoneAtTwiceTheFontScaleScrollsItsTabs() {
        val tabs = tabs("w411dp-h914dp-xxhdpi", 2.0f)
        assertEquals(TabLayout.MODE_SCROLLABLE, tabs.tabMode)
        assertOneLineEach(tabs, "phone at 2.0")
    }

    @Test
    fun aTabletsTabsShareTheWidthAtEveryFontScale() {
        for (scale in listOf(1.0f, 1.3f, 2.0f)) {
            val tabs = tabs("w924dp-h1480dp-xhdpi", scale)
            assertEquals("tablet at $scale", TabLayout.MODE_FIXED, tabs.tabMode)
            assertOneLineEach(tabs, "tablet at $scale")
            val widths = (0 until tabs.tabCount).map { tabs.getTabAt(it)!!.view.width }
            assertTrue("tablet at $scale: equal shares of the width, $widths", widths.max() - widths.min() <= 1 && widths.sum() >= tabs.width - 4)
        }
    }
}
