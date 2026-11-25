package minty.emanata.data

import android.app.Application
import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import minty.emanata.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The view ring's chosen options and the grid/list button's tap action, as stored. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RingOptionsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    @Test
    fun theDefaultsAreLayoutOnTapAndARingWithoutIt() {
        val prefs = Prefs(context)
        assertEquals(RingOption.LAYOUT, prefs.fabTap)
        assertEquals(RingOptions.DEFAULT, prefs.ringOptions)
        assertFalse(RingOption.LAYOUT in prefs.ringOptions.shown)
        // The settings screen writes the same default.
        PreferenceManager.setDefaultValues(context, R.xml.settings, true)
        assertEquals(RingOption.LAYOUT, Prefs(context).fabTap)
    }

    @Test
    fun bothPersistAndTravelInBackups() {
        val prefs = Prefs(context)
        prefs.fabTap = RingOption.SORT
        val chosen = RingOptions.DEFAULT.moved(7, 0).with(RingOption.DETAIL, false)
        prefs.ringOptions = chosen
        assertEquals(RingOption.SORT, Prefs(context).fabTap)
        assertEquals(chosen, Prefs(context).ringOptions)
        assertEquals(RingOption.TITLE_LINES, Prefs(context).ringOptions.order.first())

        val saved = prefs.exportAll()
        assertTrue(saved.keys.containsAll(listOf("pref_fab_tap", "pref_view_ring")))
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        Prefs(context).importAll(saved)
        assertEquals(RingOption.SORT, Prefs(context).fabTap)
        assertEquals(chosen, Prefs(context).ringOptions)
    }

    @Test
    fun encodingRoundTripsAndOddTextFallsBack() {
        val chosen = RingOptions.DEFAULT.with(RingOption.SORT, false).with(RingOption.TITLE_LINES, true).moved(2, 5)
        assertEquals(chosen, RingOptions.decode(chosen.encode()))
        assertEquals("smaller,larger,detail,sort,folders_first,sections,-layout,-title_lines", RingOptions.DEFAULT.encode())
        // Unknown names and repeats dropped; options it lacks follow, hidden.
        val read = RingOptions.decode("sort,bogus,smaller,sort,-layout,larger")
        assertEquals(listOf(RingOption.SORT, RingOption.SMALLER, RingOption.LAYOUT, RingOption.LARGER), read.order.take(4))
        assertEquals(setOf(RingOption.SORT, RingOption.SMALLER, RingOption.LARGER), read.shown)
        assertEquals(RingOption.entries.toSet(), read.order.toSet())
        // A tab showing fewer than three or more than six: the default.
        assertEquals(RingOptions.DEFAULT, RingOptions.decode("sort,smaller"))
        assertEquals(RingOptions.DEFAULT, RingOptions.decode("sort,smaller,folders_first,sections"))
        assertEquals(RingOptions.DEFAULT, RingOptions.decode(RingOption.entries.joinToString(",") { it.key }))
    }

    @Test
    fun everyTabShowsThreeToSixCountingTabOnlyOnesInTheirTabOnly() {
        val general = listOf(RingOption.SMALLER, RingOption.LARGER, RingOption.DETAIL, RingOption.SORT, RingOption.LAYOUT)
        val tabOnly = setOf(RingOption.FOLDERS_FIRST, RingOption.SECTIONS)
        // Five everywhere and each tab's own: six in Browse and Favorites, five in History and Bookmarks.
        assertTrue(RingOptions.fits(general.toSet() + tabOnly))
        // A seventh in Browse and Favorites.
        assertFalse(RingOptions.fits(general.toSet() + tabOnly + RingOption.TITLE_LINES))
        // Six everywhere, with no tab's own.
        assertTrue(RingOptions.fits(general.toSet() + RingOption.TITLE_LINES))
        // Two in History and Bookmarks.
        assertFalse(RingOptions.fits(general.take(2).toSet() + tabOnly))
        assertTrue(RingOptions.fits(general.take(3).toSet() + tabOnly))
    }

    @Test
    fun tabOnlyOptionsShowInTheirTabOnly() {
        assertTrue(RingOption.FOLDERS_FIRST.appliesTo(Prefs.TAB_BROWSE))
        assertFalse(RingOption.FOLDERS_FIRST.appliesTo(Prefs.TAB_FAVORITES))
        assertTrue(RingOption.SECTIONS.appliesTo(Prefs.TAB_FAVORITES))
        assertFalse(RingOption.SECTIONS.appliesTo(Prefs.TAB_HISTORY))
        for (tab in Prefs.TABS) assertTrue(RingOption.TITLE_LINES.appliesTo(tab))
    }
}
