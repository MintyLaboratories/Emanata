package com.infinity.comicreader.ui

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.SortKey
import com.infinity.comicreader.data.SortLevel
import com.infinity.comicreader.data.TabSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "sw900dp-w900dp-h1400dp")
class SortDialogTest {

    private lateinit var activity: AppCompatActivity
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        prefs = Prefs(activity)
    }

    @Test
    fun summaryNamesBothLevels() {
        val sort = TabSort(SortLevel(SortKey.YEAR), then = SortLevel(SortKey.LENGTH, flipped = true))
        assertEquals("Year – newest first, then Length – shortest first", SortDialog.summary(activity, sort))
        assertEquals("Name – A → Z", SortDialog.summary(activity, TabSort(SortLevel(SortKey.NAME))))
        // A second level after an option without ties is not in effect.
        assertEquals("Size – largest first", SortDialog.summary(activity, TabSort(SortLevel(SortKey.SIZE), SortLevel(SortKey.YEAR))))
    }

    private fun View.findText(text: String): View? {
        if (this is TextView && this.text.toString() == text) return this
        if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).findText(text)?.let { return it }
        return null
    }

    /** The clickable row holding [text]. */
    private fun View.row(text: String, nth: Int = 0): View {
        val matches = ArrayList<View>()
        fun walk(v: View) {
            if (v is TextView && v.text.toString() == text) matches += v.parent as View
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(this)
        return matches[nth]
    }

    @Test
    fun tapsApplyAtOnceAndFlipTheSelectedOption() {
        val dialog = SortDialog.create(activity, prefs, Prefs.TAB_BROWSE).apply { show() }
        shadowOf(Looper.getMainLooper()).idle()
        val root = dialog.window!!.decorView
        assertNotNull(root.findText("Name – A → Z"))

        root.row("Name").performClick()
        assertEquals(SortLevel(SortKey.NAME, flipped = true), prefs.sort(Prefs.TAB_BROWSE).by)

        root.row("Year").performClick()
        assertEquals(SortLevel(SortKey.YEAR), prefs.sort(Prefs.TAB_BROWSE).by)
        // Year has ties: "Then by" lists the other options; the second "Length" row is under it.
        root.row("Length", nth = 1).performClick()
        assertEquals(SortLevel(SortKey.LENGTH), prefs.sort(Prefs.TAB_BROWSE).then)
        root.row("Length", nth = 1).performClick()
        assertEquals(SortLevel(SortKey.LENGTH, flipped = true), prefs.sort(Prefs.TAB_BROWSE).then)
        assertNotNull(root.findText("Year – newest first, then Length – shortest first"))

        // Size has no ties: Then by is automatic.
        root.row("Size").performClick()
        assertNotNull(root.findText("Then by: Name (automatic)"))
        assertEquals(Prefs.TAB_BROWSE.let { prefs.sort(it).by }, SortLevel(SortKey.SIZE))
    }
}
