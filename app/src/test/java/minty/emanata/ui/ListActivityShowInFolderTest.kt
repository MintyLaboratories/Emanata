package minty.emanata.ui

import minty.emanata.testing.toggleFavorite
import android.Manifest
import android.app.Application
import android.os.Environment
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.tabs.TabLayout
import minty.emanata.R
import minty.emanata.testing.idleUntil
import minty.emanata.data.Library
import minty.emanata.data.Prefs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
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
        Library(context).toggleFavorite(book)
        Prefs(context).lastPath = dir.absolutePath
    }

    /** True once the screen waits on nothing but the test: listed, favorites classified, facts read, presence asked, its menu looked into. */
    private fun ListActivity.quiet() =
        listJob?.isCompleted == true && listOf(classifyJob, factsJob, presenceJob, moreLook).none { it?.isCompleted == false }

    /** Runs the main looper until the screen is quiet. */
    private fun ListActivity.settle() = idleUntil("the library screen quiet") { quiet() }

    /** The selection's ⋮ menu of the book selected on tab [tab]. */
    private fun menu(tab: Int): List<String> {
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        activity.settle()
        if (tab != 0) {
            activity.findViewById<TabLayout>(R.id.tabs).getTabAt(tab)!!.select()
            activity.settle()
        }
        val recycler = activity.findViewById<RecyclerView>(R.id.recycler)
        recycler.measure(0, 0)
        recycler.layout(0, 0, 1000, 2000)
        val adapter = recycler.adapter as BookAdapter
        recycler.findViewHolderForAdapterPosition(adapter.positionOf(book.absolutePath))!!.itemView.performLongClick()
        activity.settle()
        activity.findViewById<android.view.View>(R.id.selection_more).performClick()
        activity.settle()
        val menu = activity.moreMenu!!
        return (0 until menu.childCount).map { menu.getChildAt(it).findViewById<android.widget.TextView>(R.id.more_action_label).text.toString() }
    }

    /** No work of this test's screens runs on into the next. */
    @After
    fun awaitLeftovers() = awaitLeftoverWork()

    @Test
    fun hiddenForABookInTheFolderBrowsed() {
        assertFalse(menu(tab = 0).contains(context.getString(R.string.menu_open_location)))
    }

    @Test
    fun offeredFromFavorites() {
        assertTrue(menu(tab = 1).contains(context.getString(R.string.menu_open_location)))
    }
}
