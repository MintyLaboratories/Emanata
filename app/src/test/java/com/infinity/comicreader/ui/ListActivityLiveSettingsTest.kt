package com.infinity.comicreader.ui

import android.Manifest
import android.app.Application
import android.os.Looper
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

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
        val list = launch()
        assertFalse(list.recycler().layoutManager is GridLayoutManager)
        prefs.listGrid = true
        idle()
        assertTrue(list.recycler().layoutManager is GridLayoutManager)
    }

    @Test
    fun gridChangedElsewhereAppliesOnReturn() {
        val list = launch()
        list.pause().stop()
        // As if changed in the settings screen on top of the list.
        prefs.listGrid = true
        prefs.gridColumns = 5
        list.start().resume()
        idle()
        val lm = list.recycler().layoutManager
        assertTrue(lm is GridLayoutManager)
        assertEquals(5, (lm as GridLayoutManager).spanCount)
    }

    @Test
    fun columnsApplyLive() {
        prefs.listGrid = true
        prefs.gridColumns = 3
        val list = launch()
        prefs.gridColumns = 4
        idle()
        assertEquals(4, (list.recycler().layoutManager as GridLayoutManager).spanCount)
    }

    @Test
    fun backToListApplies() {
        prefs.listGrid = true
        val list = launch()
        list.pause().stop()
        prefs.listGrid = false
        list.start().resume()
        idle()
        assertFalse(list.recycler().layoutManager is GridLayoutManager)
    }
}
