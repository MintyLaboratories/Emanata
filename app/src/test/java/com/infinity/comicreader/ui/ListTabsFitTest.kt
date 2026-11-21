package com.infinity.comicreader.ui

import android.Manifest
import android.app.Application
import android.os.Environment
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.util.TypedValue
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.tabs.TabLayout
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
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

/**
 * The library's tab labels stay on one line at any font scale, at their own font size: tabs
 * share the width while they fit, with less side padding when needed, and scroll otherwise.
 */
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

    /** No work of this test's screens runs on into the next. */
    @After
    fun awaitLeftovers() = awaitLeftoverWork()

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

    /** Each tab's label width without its side padding. */
    private fun TabLayout.contents(): List<Int> = (0 until tabCount).map { i ->
        val v = getTabAt(i)!!.view
        v.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        v.measuredWidth - v.paddingStart - v.paddingEnd
    }

    /**
     * Tabs at the styled padding while that fits; else at the most padding (at least 4 dp) that
     * fits; else scrolling at the styled padding. Labels keep the one-line font size.
     */
    private fun assertPadding(tabs: TabLayout, what: String) {
        val density = tabs.resources.displayMetrics.density
        val styled = (12 * density).toInt()
        val least = (FitTabLayout.MIN_PAD_DP * density).toInt()
        val pads = (tabs as FitTabLayout).tabPaddings
        assertEquals("$what: one padding for all, $pads", 1, pads.distinct().size)
        val pad = pads.first()
        val share = tabs.width / tabs.tabCount
        val widest = tabs.contents().max()
        if (tabs.tabMode == TabLayout.MODE_FIXED) {
            assertTrue("$what: $pad between $least and $styled", pad in least..styled)
            assertTrue("$what: fits at $pad", widest + 2 * pad <= share)
            if (pad < styled) assertTrue("$what: one pixel more would not fit", widest + 2 * (pad + 1) > share)
        } else {
            assertEquals("$what: scrolls at the styled padding", styled, pad)
            assertTrue("$what: even the least padding would not fit", widest + 2 * least > share)
        }
        val size = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, tabs.resources.displayMetrics)
        for (label in tabs.labels()) assertEquals("$what: \"${label.text}\" at its own size", size, label.textSize, 0.5f)
    }

    @Test
    fun paddingShrinksBeforeTabsScrollOnPhonesAndTablets() {
        val modes = HashSet<String>()
        for (screen in listOf("w360dp-h780dp-xxhdpi", "w411dp-h914dp-xxhdpi", "w924dp-h1480dp-xhdpi")) {
            for (scale in listOf(1.0f, 1.3f, 2.0f)) {
                val tabs = tabs(screen, scale)
                assertPadding(tabs, "$screen at $scale")
                assertOneLineEach(tabs, "$screen at $scale")
                val pad = (tabs as FitTabLayout).tabPaddings.first()
                modes += if (tabs.tabMode == TabLayout.MODE_SCROLLABLE) "scroll" else if (pad < (12 * tabs.resources.displayMetrics.density).toInt()) "less" else "styled"
            }
        }
        assertEquals("every step shows somewhere", setOf("styled", "less", "scroll"), modes)
    }

    @Test
    fun aLabelWiderThanAnyTabStaysOnOneLineAtItsOwnSizeAndColors() {
        val tabs = tabs("w411dp-h914dp-xxhdpi", 2.0f)
        tabs.getTabAt(1)!!.text = "Favorites of every kind, kept here for reading later on"
        val width = tabs.resources.displayMetrics.widthPixels
        tabs.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        tabs.layout(0, 0, tabs.measuredWidth, tabs.measuredHeight)
        assertEquals(TabLayout.MODE_SCROLLABLE, tabs.tabMode)
        val size = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, tabs.resources.displayMetrics)
        for (label in tabs.labels()) {
            assertEquals("\"${label.text}\" on one line", 1, label.layout.lineCount)
            assertEquals("\"${label.text}\" at its own size", size, label.textSize, 0.5f)
            assertEquals(tabs.tabTextColors, label.textColors)
        }
        // Only the one wider than any tab is cut.
        assertTrue(tabs.labels()[1].layout.getEllipsisCount(0) > 0)
    }

    @Test
    fun paddingPicksTheMostThatFits() {
        // Labels 80 and 90 wide in shares of 110: 10 a side fits, 11 does not.
        assertEquals(10, TabPadding.pick(listOf(80, 90), listOf(0, 0), 110, 36, 12 - 2))
        assertEquals(36, TabPadding.pick(listOf(20, 30), listOf(0, 0), 110, 36, 12))
        assertEquals(null, TabPadding.pick(listOf(80, 100), listOf(0, 0), 110, 36, 12))
        // A short label within its minimum width fits either way.
        assertEquals(36, TabPadding.pick(listOf(30), listOf(120), 80, 36, 12))
    }
}
