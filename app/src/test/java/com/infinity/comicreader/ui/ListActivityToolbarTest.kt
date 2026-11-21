package com.infinity.comicreader.ui

import com.infinity.comicreader.testing.bookId
import android.Manifest
import android.app.Application
import android.os.Environment
import android.view.Menu
import android.widget.CheckBox
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.appbar.MaterialToolbar
import com.infinity.comicreader.R
import com.infinity.comicreader.testing.idleUntil
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ToolbarButton
import com.infinity.comicreader.data.ToolbarButtons
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
import java.io.File

/** Sort, Filter, Favorite and Search sit on the library toolbar in the order the setting gives. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ListActivityToolbarTest {

    private lateinit var context: Application
    private lateinit var prefs: Prefs
    private lateinit var dir: File

    private val barIds = listOf(R.id.menu_bar_sort, R.id.menu_bar_filter, R.id.menu_bar_favorite, R.id.menu_search)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        for (name in listOf("ui", "library")) context.getSharedPreferences(name, 0).edit().clear().commit()
        prefs = Prefs(context)
        dir = File(Environment.getExternalStorageDirectory(), "shelf").apply { mkdirs() }
        File(dir, "one.cbz").createNewFile()
        prefs.lastPath = dir.absolutePath
    }

    /** True once the screen waits on nothing but the test: listed, favorites classified, facts read, presence asked, its menu looked into. */
    private fun ListActivity.quiet() =
        listJob?.isCompleted == true && listOf(classifyJob, factsJob, presenceJob, moreLook).none { it?.isActive == true }

    /** Runs the main looper until the screen is quiet. */
    private fun ListActivity.settle() = idleUntil("the library screen quiet") { quiet() }

    private fun launch(): ListActivity = Robolectric.buildActivity(ListActivity::class.java).setup().get().also { it.settle() }

    private fun ListActivity.menu(): Menu = findViewById<MaterialToolbar>(R.id.toolbar).menu

    /** The toolbar buttons shown, in menu order. */
    private fun ListActivity.buttons(): List<Int> {
        val menu = menu()
        return (0 until menu.size()).map { menu.getItem(it) }.filter { it.itemId in barIds && it.isVisible }.map { it.itemId }
    }

    @Test
    fun defaultButtonsAreSortFilterFavoriteSearch() {
        val activity = launch()
        assertEquals(barIds, activity.buttons())
        // The menu leaves out what the toolbar shows.
        assertFalse(activity.menu().findItem(R.id.menu_sort).isVisible)
        assertFalse(activity.menu().findItem(R.id.menu_add_favorite).isVisible)
    }

    @Test
    fun favoriteButtonStarsTheFolder() {
        val activity = launch()
        val star = activity.menu().findItem(R.id.menu_bar_favorite)
        assertEquals(context.getString(R.string.menu_add_favorite), star.title)
        activity.onOptionsItemSelected(star)
        activity.settle()
        assertTrue(Library(context).isFavoriteFolder(dir.absolutePath))
        assertEquals(context.getString(R.string.menu_remove_favorite), activity.menu().findItem(R.id.menu_bar_favorite).title)
    }

    @Test
    fun searchingHidesTheOtherButtons() {
        val activity = launch()
        val search = activity.menu().findItem(R.id.menu_search)
        search.expandActionView()
        activity.settle()
        assertEquals(listOf(R.id.menu_search), activity.buttons())
        search.collapseActionView()
        activity.settle()
        assertEquals(barIds, activity.buttons())
    }

    @Test
    fun theSettingOrdersAndHidesButtonsLive() {
        val activity = launch()
        prefs.toolbarButtons = ToolbarButtons(
            listOf(ToolbarButton.SEARCH, ToolbarButton.FAVORITE, ToolbarButton.SORT, ToolbarButton.FILTER),
            setOf(ToolbarButton.SEARCH, ToolbarButton.FAVORITE, ToolbarButton.FILTER)
        )
        activity.settle()
        assertEquals(listOf(R.id.menu_search, R.id.menu_bar_favorite, R.id.menu_bar_filter), activity.buttons())
        // A hidden button stays reachable from the menu.
        assertTrue(activity.menu().findItem(R.id.menu_sort).isVisible)
    }

    @Test
    fun dialogCheckboxesShowAndHideButtons() {
        val activity = launch()
        val dialog: AlertDialog = ToolbarButtonsDialog.show(activity, prefs)
        activity.settle()
        val list = dialog.findViewById<RecyclerView>(R.id.toolbar_buttons_list)!!
        list.measure(0, 0)
        list.layout(0, 0, 800, 1200)
        list.findViewHolderForAdapterPosition(1)!!.itemView.findViewById<CheckBox>(R.id.toolbar_button_shown).performClick()
        assertEquals(setOf(ToolbarButton.SORT, ToolbarButton.FAVORITE, ToolbarButton.SEARCH), prefs.toolbarButtons.shown)
        assertEquals("Sort, Favorite, Search", ToolbarButtonsDialog.summary(activity, prefs))
    }
}
