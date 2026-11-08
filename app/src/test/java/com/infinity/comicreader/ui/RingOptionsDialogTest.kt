package com.infinity.comicreader.ui

import android.app.Application
import android.os.Looper
import android.widget.CheckBox
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.RingOption
import com.infinity.comicreader.data.RingOptions
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

/** Settings → View ring options: 3 to 6 checked, a box past either end springs back; and the button's tap choices. */
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
    fun aSeventhOrAThirdFromLastSpringsBack() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        val dialog: AlertDialog = RingOptionsDialog.show(activity, prefs)
        idle()
        val list = dialog.findViewById<RecyclerView>(R.id.view_ring_list)!!
        val order = prefs.ringOptions.order
        val layout = order.indexOf(RingOption.LAYOUT)
        // Six shown: a seventh is refused.
        list.box(layout).performClick()
        idle()
        assertEquals(RingOptions.DEFAULT, prefs.ringOptions)
        assertFalse(list.box(layout).isChecked)
        // Down to three, and no further.
        for (o in listOf(RingOption.SECTIONS, RingOption.FOLDERS_FIRST, RingOption.DETAIL)) list.box(order.indexOf(o)).performClick()
        idle()
        assertEquals(listOf(RingOption.SMALLER, RingOption.LARGER, RingOption.SORT), prefs.ringOptions.visible)
        list.box(order.indexOf(RingOption.SORT)).performClick()
        idle()
        assertTrue(list.box(order.indexOf(RingOption.SORT)).isChecked)
        assertEquals(3, prefs.ringOptions.visible.size)
        assertEquals("Smaller, Larger, Sort", RingOptionsDialog.summary(activity, prefs))
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
