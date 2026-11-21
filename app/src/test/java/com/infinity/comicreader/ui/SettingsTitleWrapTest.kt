package com.infinity.comicreader.ui

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Setting titles wrap to more lines on a narrow screen instead of being cut. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsTitleWrapTest {

    private lateinit var context: Context

    private val long = "A setting title far too long to fit on a single line of a narrow phone screen"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().clear().commit()
        // Every section open and every setting shown, so each row type is in the list.
        Prefs(context).apply {
            keepSimple = false
            collapsedSettings = emptySet()
        }
    }

    private fun open(): PreferenceFragmentCompat {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        return activity.supportFragmentManager.findFragmentById(R.id.settings_container) as PreferenceFragmentCompat
    }

    private fun all(group: PreferenceGroup): List<Preference> =
        (0 until group.preferenceCount).map { group.getPreference(it) }.flatMap { listOf(it) + if (it is PreferenceGroup) all(it) else emptyList() }

    /** Every row the list shows, each bound to a fresh view of its own type and laid out [width] px wide. */
    private fun rows(fragment: PreferenceFragmentCompat, width: Int): List<Pair<Preference, View>> {
        val list = fragment.listView
        val adapter = list.adapter as PreferenceGroupAdapter
        return (0 until adapter.itemCount).map { i ->
            val holder = adapter.onCreateViewHolder(list, adapter.getItemViewType(i))
            adapter.onBindViewHolder(holder, i)
            adapter.getItem(i)!! to holder.itemView.also { layOut(it, width) }
        }
    }

    private fun layOut(view: View, width: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, width, view.measuredHeight)
    }

    private val phoneWidth get() = (320 * context.resources.displayMetrics.density).toInt()

    @Test
    fun noSettingKeepsItsTitleToOneLine() {
        val fragment = open()
        val single = all(fragment.preferenceScreen).filter { it.isSingleLineTitle }.map { it.key }
        assertEquals(emptyList<String>(), single)
    }

    @Test
    fun everyRowTypeWrapsALongTitle() {
        val fragment = open()
        val rows = rows(fragment, phoneWidth)
        val types = rows.map { it.first.javaClass }.toSet()
        // Switches, lists, sliders, plain rows, sections and the custom AI rows.
        for (type in listOf(
            androidx.preference.SwitchPreferenceCompat::class.java, androidx.preference.ListPreference::class.java,
            androidx.preference.SeekBarPreference::class.java, Preference::class.java, CollapsibleCategory::class.java,
            AiChainPreference::class.java, AiSizePreference::class.java
        )) assertTrue("${type.simpleName} not in the list", type in types)
        for ((pref, row) in rows) {
            val title = row.findViewById<TextView>(android.R.id.title) ?: continue
            title.text = long
            layOut(row, phoneWidth)
            assertTrue("${pref.key} (${pref.javaClass.simpleName}) title takes ${title.lineCount} line", title.lineCount > 1)
            assertFalse("${pref.key} title is cut", (0 until title.lineCount).any { title.layout.getEllipsisCount(it) > 0 })
        }
    }

    @Test
    fun rowsInSectionsCollapsedAtOpenWrapOnceExpanded() {
        val sections = all(open().preferenceScreen).filterIsInstance<CollapsibleCategory>().map { it.key }
        assertTrue(sections.size > 3)
        Prefs(context).collapsedSettings = sections.toSet()
        val fragment = open()
        val categories = all(fragment.preferenceScreen).filterIsInstance<CollapsibleCategory>()
        assertTrue(categories.all { it.collapsed })
        categories.forEach { it.performClick() }
        shadowOf(Looper.getMainLooper()).idle()
        val rows = rows(fragment, phoneWidth)
        assertTrue(rows.size > sections.size * 2)
        for ((pref, row) in rows) {
            val title = row.findViewById<TextView>(android.R.id.title) ?: continue
            title.text = long
            layOut(row, phoneWidth)
            assertTrue("${pref.key} title takes ${title.lineCount} line", title.lineCount > 1)
        }
    }

    @Test
    fun theMirrorZonesTitleShowsInFullOnANarrowPhone() {
        val fragment = open()
        val (_, row) = rows(fragment, phoneWidth).first { it.first.key == "pref_tap_zones_mirror" }
        val title = row.findViewById<TextView>(android.R.id.title)
        assertEquals(context.getString(R.string.pref_tap_zones_mirror), title.text.toString())
        assertTrue(title.lineCount > 1)
        assertTrue((0 until title.lineCount).none { title.layout.getEllipsisCount(it) > 0 })
    }

    @Test
    fun summariesStillWrap() {
        val fragment = open()
        var checked = 0
        for ((pref, row) in rows(fragment, phoneWidth)) {
            val summary = row.findViewById<TextView>(android.R.id.summary) ?: continue
            if (summary.visibility != View.VISIBLE) continue
            summary.text = long
            layOut(row, phoneWidth)
            assertTrue("${pref.key} summary takes ${summary.lineCount} line", summary.lineCount > 1)
            checked++
        }
        assertTrue(checked > 3)
    }
}
