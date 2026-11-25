package minty.emanata.ui

import android.app.Application
import android.os.Looper
import android.widget.CheckBox
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import minty.emanata.R
import minty.emanata.data.Prefs
import minty.emanata.data.RingOption
import minty.emanata.data.RingOptions
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

/** Settings → View ring options: 3 to 6 per tab, a box past either end springs back; and the button's tap choices. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RingOptionsDialogTest {

    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        prefs = Prefs(context)
    }

    private fun idle() = repeat(3) { shadowOf(Looper.getMainLooper()).idle() }

    /** The checkbox at [position], laid out anew after what changed. */
    private fun RecyclerView.box(position: Int): CheckBox {
        idle()
        measure(0, 0)
        layout(0, 0, 800, 1600)
        return findViewHolderForAdapterPosition(position)!!.itemView.findViewById(R.id.toolbar_button_shown)
    }

    @Test
    fun aSeventhOrASecondInATabSpringsBack() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        val dialog: AlertDialog = RingOptionsDialog.show(activity, prefs)
        idle()
        val list = dialog.findViewById<RecyclerView>(R.id.view_ring_list)!!
        val order = prefs.ringOptions.order
        val layout = order.indexOf(RingOption.LAYOUT)
        val titles = order.indexOf(RingOption.TITLE_LINES)
        // Browse and Favorites show five each: a sixth fits, Folders first and Sections counting in their own tab only.
        list.box(layout).performClick()
        idle()
        assertTrue(list.box(layout).isChecked)
        assertEquals(RingOptions.DEFAULT.with(RingOption.LAYOUT, true), prefs.ringOptions)
        // A seventh in Browse is refused.
        list.box(titles).performClick()
        idle()
        assertFalse(list.box(titles).isChecked)
        assertEquals(7, prefs.ringOptions.visible.size)
        // Down to three in History and Bookmarks, and no further.
        for (o in listOf(RingOption.LAYOUT, RingOption.DETAIL)) list.box(order.indexOf(o)).performClick()
        idle()
        assertEquals(listOf(RingOption.SMALLER, RingOption.LARGER, RingOption.SORT), prefs.ringOptions.forTab(Prefs.TAB_HISTORY))
        list.box(order.indexOf(RingOption.SORT)).performClick()
        idle()
        assertTrue(list.box(order.indexOf(RingOption.SORT)).isChecked)
        assertEquals("Smaller, Larger, Sort, Folders first (Browse), Sections (Favorites)", RingOptionsDialog.summary(activity, prefs))
    }

    @Test
    fun bothSettingsNeedTheGridListButton() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        idle()
        val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings_container) as PreferenceFragmentCompat
        val button = fragment.findPreference<SwitchPreferenceCompat>("pref_list_quickfab")!!
        val ring = listOf("pref_fab_tap", "view_ring").map { fragment.findPreference<Preference>(it)!! }
        assertTrue(button.isChecked)
        for (p in ring) assertTrue(p.key, p.isEnabled)
        button.performClick()
        idle()
        assertFalse(button.isChecked)
        for (p in ring) assertFalse(p.key, p.isEnabled)
    }

    @Test
    fun theTapOffersEveryActionNamingTabOnlyOnes() {
        val (names, values) = RingOptionsDialog.tapChoices(ApplicationProvider.getApplicationContext())
        assertEquals(RingOption.entries.map { it.key }, values.toList())
        assertEquals("Layout", names.first())
        assertTrue(names.contains("Folders first (Browse)"))
        assertTrue(names.contains("Sections (Favorites)"))
        assertTrue(names.contains("Title lines"))
    }
}
