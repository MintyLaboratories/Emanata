package com.infinity.comicreader.ui

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import androidx.appcompat.widget.SearchView
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.appbar.MaterialToolbar
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** Settings search finds settings by title or summary and opens them; one button collapses or expands every section. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsSearchTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun open(): Pair<ActivityController<SettingsActivity>, SettingsFragment> {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        idle()
        val fragment = controller.get().supportFragmentManager.findFragmentById(R.id.settings_container) as SettingsFragment
        return controller to fragment
    }

    private fun SettingsActivity.item(id: Int) = findViewById<MaterialToolbar>(R.id.settings_toolbar).menu.findItem(id)

    private fun SettingsFragment.pref(key: String) = findPreference<Preference>(key)!!

    /** Opens the search and types [text]. */
    private fun search(activity: SettingsActivity, text: String) {
        val item = activity.item(R.id.settings_search)
        item.expandActionView()
        idle()
        (item.actionView as SearchView).setQuery(text, false)
        idle()
    }

    private fun resultsList(fragment: SettingsFragment) = fragment.requireView().findViewById<RecyclerView>(R.id.settings_results_list)

    @Test
    fun findsATitleWhateverTheCaseAndShowsItsSection() {
        val (controller, fragment) = open()
        search(controller.get(), "KEEP THE SCREEN")
        // The title match first; a summary holding every word follows.
        val found = fragment.shownResults().first()
        assertEquals("pref_keep_screen_on", found.key)
        assertEquals(context.getString(R.string.pref_cat_reader), found.place)
        assertEquals(View.VISIBLE, fragment.requireView().findViewById<View>(R.id.settings_results).visibility)
        val list = resultsList(fragment)
        list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
        list.layout(0, 0, 1080, 1920)
        val row = list.getChildAt(0)
        assertEquals(context.getString(R.string.pref_keep_on), row.findViewById<android.widget.TextView>(R.id.setting_result_title).text.toString())
        assertEquals(context.getString(R.string.pref_cat_reader), row.findViewById<android.widget.TextView>(R.id.setting_result_place).text.toString())
    }

    @Test
    fun findsASummary() {
        val (controller, fragment) = open()
        search(controller.get(), "gliding")
        assertEquals(listOf("pref_fling"), fragment.shownResults().map { it.key })
    }

    @Test
    fun saysWhenNothingMatches() {
        val (controller, fragment) = open()
        search(controller.get(), "zzqx")
        assertTrue(fragment.shownResults().isEmpty())
        assertEquals(View.VISIBLE, fragment.requireView().findViewById<View>(R.id.settings_results_empty).visibility)
    }

    @Test
    fun anAdvancedSettingIsFoundUnderKeepItSimpleTaggedAndOpensWithoutTurningAnythingOn() {
        val (controller, fragment) = open()
        assertTrue(Prefs(context).keepSimple)
        assertFalse(fragment.pref("pref_sharpen").isVisible)
        search(controller.get(), "rcas")
        val found = fragment.shownResults().single()
        assertEquals("pref_sharpen", found.key)
        assertTrue(found.advanced)
        assertTrue(found.title.toString().endsWith(context.getString(R.string.advanced_tag)))
        // The tag is no text to match: searching for it finds no advanced setting by it.
        assertEquals(context.getString(R.string.pref_sharpen), found.plainTitle)

        val list = resultsList(fragment)
        list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
        list.layout(0, 0, 1080, 1920)
        list.getChildAt(0).performClick()
        idle()
        assertFalse(controller.get().item(R.id.settings_search).isActionViewExpanded)
        assertTrue(fragment.pref("pref_sharpen").isVisible)
        assertTrue(Prefs(context).keepSimple)
        // Other advanced settings stay hidden.
        assertFalse(fragment.pref("pref_scale_pages").isVisible)
        assertEquals("pref_sharpen", fragment.flashing)
    }

    @Test
    fun searchingForTheTagAloneFindsNoAdvancedSettingByIt() {
        val (controller, fragment) = open()
        search(controller.get(), context.getString(R.string.advanced_tag))
        assertFalse(fragment.shownResults().any { it.key == "pref_sharpen" })
    }

    @Test
    fun anAiStepIsPlacedUnderItsChain() {
        val (controller, fragment) = open()
        Prefs(context).keepSimple = false
        search(controller.get(), context.getString(R.string.pref_ai_artifacts))
        val places = fragment.shownResults().filter { it.key == "pref_ai_artifacts" || it.key == "pref_ai_manga_artifacts" }.associate { it.key to it.place }
        val ai = context.getString(R.string.pref_cat_ai)
        assertEquals(ai + SettingsSearch.PLACE_SEPARATOR + context.getString(R.string.ai_chain_manga), places["pref_ai_manga_artifacts"])
        assertEquals(ai + SettingsSearch.PLACE_SEPARATOR + context.getString(R.string.ai_chain_other), places["pref_ai_artifacts"])
    }

    @Test
    fun openingAResultExpandsItsSectionScrollsAndTintsBriefly() {
        val (controller, fragment) = open()
        (fragment.pref("section_network") as CollapsibleCategory).performClick()
        assertFalse(fragment.pref("pref_similar_shares").isVisible)
        fragment.reveal("pref_similar_shares")
        idle()
        assertFalse((fragment.pref("section_network") as CollapsibleCategory).collapsed)
        assertFalse("section_network" in Prefs(context).collapsedSettings)
        assertTrue(fragment.pref("pref_similar_shares").isVisible)
        assertEquals("pref_similar_shares", fragment.flashing)
        shadowOf(Looper.getMainLooper()).idleFor(SettingFlash.HOLD_MS + SettingFlash.FADE_MS + 100, TimeUnit.MILLISECONDS)
        assertNull(fragment.flashing)
        controller.pause()
    }

    @Test
    fun closingTheSearchHidesTheResults() {
        val (controller, fragment) = open()
        search(controller.get(), "gap")
        assertTrue(fragment.shownResults().isNotEmpty())
        controller.get().item(R.id.settings_search).collapseActionView()
        idle()
        assertTrue(fragment.shownResults().isEmpty())
        assertEquals(View.GONE, fragment.requireView().findViewById<View>(R.id.settings_results).visibility)
    }

    @Test
    fun theButtonCollapsesAllWhileAnySectionIsExpandedElseExpandsAll() {
        val (controller, fragment) = open()
        val activity = controller.get()
        assertEquals(context.getString(R.string.settings_collapse_all), activity.item(R.id.settings_fold_all).title.toString())
        // One section collapsed, others expanded: still Collapse all.
        (fragment.pref("section_reader") as CollapsibleCategory).performClick()
        idle()
        assertEquals(context.getString(R.string.settings_collapse_all), activity.item(R.id.settings_fold_all).title.toString())

        assertTrue(shadowOf(activity).clickMenuItem(R.id.settings_fold_all))
        idle()
        val sections = SettingsFolds.sections(fragment.preferenceScreen)
        assertTrue(sections.size > 3)
        assertTrue(sections.all { it.collapsed })
        assertEquals(sections.map { it.key }.toSet(), Prefs(context).collapsedSettings)
        assertFalse(fragment.pref("pref_fullscreen").isVisible)
        assertFalse(fragment.pref("pref_similar_shares").isVisible)
        // General does not fold.
        assertTrue(fragment.pref("pref_theme").isVisible)
        assertEquals(context.getString(R.string.settings_expand_all), activity.item(R.id.settings_fold_all).title.toString())

        // Expanding one by hand makes it Collapse all again.
        (fragment.pref("section_network") as CollapsibleCategory).performClick()
        idle()
        assertEquals(context.getString(R.string.settings_collapse_all), activity.item(R.id.settings_fold_all).title.toString())
        (fragment.pref("section_network") as CollapsibleCategory).performClick()
        idle()

        assertTrue(shadowOf(activity).clickMenuItem(R.id.settings_fold_all))
        idle()
        assertTrue(sections.none { it.collapsed })
        assertEquals(emptySet<String>(), Prefs(context).collapsedSettings)
        assertTrue(fragment.pref("pref_fullscreen").isVisible)
        // Advanced settings stay hidden under Keep it simple.
        assertFalse(fragment.pref("pref_scale_pages").isVisible)
        assertEquals(context.getString(R.string.settings_collapse_all), activity.item(R.id.settings_fold_all).title.toString())
    }

    @Test
    fun theButtonStepsAsideWhileSearching() {
        val (controller, _) = open()
        val activity = controller.get()
        search(activity, "page")
        assertFalse(activity.item(R.id.settings_fold_all).isVisible)
        activity.item(R.id.settings_search).collapseActionView()
        idle()
        assertTrue(activity.item(R.id.settings_fold_all).isVisible)
    }

    @Test
    fun foldingASectionLeavesTheOpenSearchFieldAlone() {
        val (controller, fragment) = open()
        val activity = controller.get()
        val item = activity.item(R.id.settings_search)
        item.expandActionView()
        idle()
        val field = item.actionView
        (fragment.pref("section_network") as CollapsibleCategory).performClick()
        idle()
        assertSame(field, activity.item(R.id.settings_search).actionView)
        assertTrue(activity.item(R.id.settings_search).isActionViewExpanded)
        assertFalse(activity.item(R.id.settings_fold_all).isVisible)
    }

    @Test
    fun theSearchSurvivesTheScreenBeingMadeAgain() {
        val (controller, _) = open()
        search(controller.get(), "gliding")
        controller.recreate()
        idle()
        val fragment = controller.get().supportFragmentManager.findFragmentById(R.id.settings_container) as SettingsFragment
        assertTrue(controller.get().item(R.id.settings_search).isActionViewExpanded)
        assertEquals(listOf("pref_fling"), fragment.shownResults().map { it.key })
    }

    @Test
    fun theSearchSurvivesLeavingTheAppAndComingBack() {
        val (controller, fragment) = open()
        search(controller.get(), "gliding")
        controller.pause()
        idle()
        controller.stop()
        idle()
        controller.restart().resume()
        idle()
        assertTrue(controller.get().item(R.id.settings_search).isActionViewExpanded)
        assertEquals(listOf("pref_fling"), fragment.shownResults().map { it.key })
    }

    @Test
    fun theSearchSurvivesTheToolbarBeingMadeAgain() {
        val (controller, fragment) = open()
        val activity = controller.get()
        search(activity, "gliding")
        activity.invalidateMenu()
        idle()
        val item = activity.item(R.id.settings_search)
        assertTrue(item.isActionViewExpanded)
        assertEquals("gliding", (item.actionView as SearchView).query.toString())
        assertEquals(listOf("pref_fling"), fragment.shownResults().map { it.key })
    }
}
