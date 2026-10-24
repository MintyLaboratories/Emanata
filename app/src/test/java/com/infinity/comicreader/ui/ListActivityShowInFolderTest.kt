package com.infinity.comicreader.ui

import android.Manifest
import android.app.Application
import android.os.Environment
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.tabs.TabLayout
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** "Show in folder" is offered only where it leads somewhere new. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ListActivityShowInFolderTest {

    private lateinit var context: Application
    private lateinit var book: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        for (name in listOf("ui", "library", "favorite_kinds")) context.getSharedPreferences(name, 0).edit().clear().commit()
        val dir = File(Environment.getExternalStorageDirectory(), "shelf").apply { mkdirs() }
        book = File(dir, "one.cbz").apply { createNewFile() }
        Library(context).toggleFavorite(book.absolutePath)
        Prefs(context).lastPath = dir.absolutePath
    }

    private fun idle() = repeat(10) {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(20)
    }

    /** The long-press menu of the first row on tab [tab]. */
    private fun menu(tab: Int): List<String> {
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        if (tab != 0) {
            activity.findViewById<TabLayout>(R.id.tabs).getTabAt(tab)!!.select()
            idle()
        }
        val recycler = activity.findViewById<RecyclerView>(R.id.recycler)
        recycler.measure(0, 0)
        recycler.layout(0, 0, 1000, 2000)
        val adapter = recycler.adapter as BookAdapter
        recycler.findViewHolderForAdapterPosition(adapter.positionOf(book.absolutePath))!!.itemView.performLongClick()
        idle()
        val list = (ShadowDialog.getLatestDialog() as AlertDialog).listView
        return (0 until list.adapter.count).map { list.adapter.getItem(it).toString() }
    }

    @Test
    fun hiddenForABookInTheFolderBrowsed() {
        assertFalse(menu(tab = 0).contains(context.getString(R.string.menu_open_location)))
    }

    @Test
    fun offeredFromFavorites() {
        assertTrue(menu(tab = 1).contains(context.getString(R.string.menu_open_location)))
    }
}
