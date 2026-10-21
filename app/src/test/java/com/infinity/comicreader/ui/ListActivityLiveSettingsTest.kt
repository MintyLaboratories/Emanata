package com.infinity.comicreader.ui

import android.Manifest
import android.app.Application
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
// Before API 30 storage access is a runtime permission, which the test can grant.
@Config(sdk = [29])
class ListActivityLiveSettingsTest {

    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        prefs = Prefs(context)
    }

    private fun launch(): ActivityController<ListActivity> =
        Robolectric.buildActivity(ListActivity::class.java).setup()

    private fun ActivityController<ListActivity>.recycler(): RecyclerView =
        get().findViewById(R.id.recycler)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun gridAppliesWhileVisible() {
        prefs.setGrid(Prefs.TAB_BROWSE, false)
        val list = launch()
        assertFalse(list.recycler().layoutManager is GridLayoutManager)
        prefs.setGrid(Prefs.TAB_BROWSE, true)
        idle()
        assertTrue(list.recycler().layoutManager is GridLayoutManager)
    }

    @Test
    fun gridChangedElsewhereAppliesOnReturn() {
        prefs.setGrid(Prefs.TAB_BROWSE, false)
        val list = launch()
        list.pause().stop()
        // As if changed in the settings screen on top of the list.
        prefs.setGrid(Prefs.TAB_BROWSE, true)
        prefs.setGridSize(Prefs.TAB_BROWSE, 5, 0)
        list.start().resume()
        idle()
        val lm = list.recycler().layoutManager
        assertTrue(lm is GridLayoutManager)
        assertEquals(5, (lm as GridLayoutManager).spanCount)
    }

    @Test
    fun columnsApplyLive() {
        prefs.setGridSize(Prefs.TAB_BROWSE, 3, 0)
        val list = launch()
        prefs.setGridSize(Prefs.TAB_BROWSE, 4, 0)
        idle()
        assertEquals(4, (list.recycler().layoutManager as GridLayoutManager).spanCount)
    }

    @Test
    fun cellsTakeColumnWidth() {
        prefs.setGridSize(Prefs.TAB_BROWSE, 4, 0)
        val list = launch()
        idle()
        val recycler = list.recycler()
        val adapter = recycler.adapter as BookAdapter
        assertTrue(recycler.width > 0)
        assertEquals((recycler.width - recycler.paddingLeft - recycler.paddingRight) / 4, adapter.cellWidth)
        prefs.setGridSize(Prefs.TAB_BROWSE, 2, 0)
        idle()
        assertEquals((recycler.width - recycler.paddingLeft - recycler.paddingRight) / 2, adapter.cellWidth)
    }

    @Test
    fun backToListApplies() {
        val list = launch()
        list.pause().stop()
        prefs.setGrid(Prefs.TAB_BROWSE, false)
        list.start().resume()
        idle()
        assertFalse(list.recycler().layoutManager is GridLayoutManager)
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp")
    fun rowsFitAboveTheBottomPadding() {
        prefs.setGridSize(Prefs.TAB_BROWSE, 0, 2)
        val list = launch()
        idle()
        val recycler = list.recycler()
        val adapter = recycler.adapter as BookAdapter
        assertTrue(adapter.cellHeight > 0)
        // Both rows fit in the space the floating button's padding leaves.
        assertTrue(2 * adapter.cellHeight <= recycler.height - recycler.paddingTop - recycler.paddingBottom)
    }

    @Test
    fun gridDimensionsAreInTheMenu() {
        val list = launch()
        val activity = list.get()
        activity.invalidateOptionsMenu()
        idle()
        val item = activity.findViewById<MaterialToolbar>(R.id.toolbar).menu.findItem(R.id.menu_grid_size)
        assertTrue(item.isVisible)
        activity.onOptionsItemSelected(item)
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val picker = dialog.findViewById<GridPicker>(R.id.grid_size)!!
        picker.onPick!!.invoke(4, 3)
        idle()
        assertEquals(4, prefs.view(Prefs.TAB_BROWSE).columns)
        assertEquals(3, prefs.view(Prefs.TAB_BROWSE).rows)
        assertFalse(dialog.isShowing)
    }

    @Test
    fun eachTabKeepsItsOwnView() {
        val list = launch()
        idle()
        assertTrue(list.recycler().layoutManager is GridLayoutManager)
        list.get().findViewById<com.google.android.material.tabs.TabLayout>(R.id.tabs).getTabAt(2)!!.select()
        idle()
        // History starts as a list; the menu's grid toggle changes only History.
        assertFalse(list.recycler().layoutManager is GridLayoutManager)
        list.get().findViewById<FloatingActionButton>(R.id.fab).performClick()
        idle()
        assertTrue(prefs.view(Prefs.TAB_HISTORY).grid)
        assertTrue(prefs.view(Prefs.TAB_BROWSE).grid)
        prefs.setGrid(Prefs.TAB_BROWSE, false)
        idle()
        assertTrue(list.recycler().layoutManager is GridLayoutManager)
    }
}
