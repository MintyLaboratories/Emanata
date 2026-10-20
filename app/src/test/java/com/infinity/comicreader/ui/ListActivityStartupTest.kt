package com.infinity.comicreader.ui

import android.Manifest
import android.app.Application
import android.os.Bundle
import android.os.Environment
import android.os.Looper
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.tabs.TabLayout
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ListActivityStartupTest {

    private lateinit var prefs: Prefs
    private lateinit var root: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        for (name in listOf("ui", "library", "favorite_kinds")) context.getSharedPreferences(name, 0).edit().clear().commit()
        prefs = Prefs(context)
        root = Environment.getExternalStorageDirectory()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun ListActivity.tab() = findViewById<TabLayout>(R.id.tabs).selectedTabPosition

    private fun ListActivity.crumbPaths() = findViewById<CrumbBar>(R.id.pathbar).crumbs.map { it.path }

    @Test
    fun opensOnBrowseByDefault() {
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        assertEquals(0, activity.tab())
    }

    @Test
    fun opensOnTheStartupTabWithBrowseBehindIt() {
        val dir = File(root, "Comics").apply { mkdirs() }
        prefs.lastPath = dir.absolutePath
        prefs.startupTab = Prefs.TAB_HISTORY
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        assertEquals(2, activity.tab())
        activity.findViewById<TabLayout>(R.id.tabs).getTabAt(0)!!.select()
        idle()
        assertEquals(dir.absolutePath, activity.crumbPaths().last())
    }

    @Test
    fun aRecreateKeepsTheTabAndFolder() {
        val dir = File(root, "Kept").apply { mkdirs() }
        prefs.lastPath = dir.absolutePath
        prefs.startupTab = Prefs.TAB_FAVORITES
        val first = Robolectric.buildActivity(ListActivity::class.java).setup()
        idle()
        first.get().findViewById<TabLayout>(R.id.tabs).getTabAt(3)!!.select()
        idle()
        val state = Bundle()
        first.saveInstanceState(state)
        val second = Robolectric.buildActivity(ListActivity::class.java).setup(state)
        idle()
        assertEquals(3, second.get().tab())
    }
}
