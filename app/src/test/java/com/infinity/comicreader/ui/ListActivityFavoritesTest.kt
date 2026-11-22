package com.infinity.comicreader.ui

import com.infinity.comicreader.testing.toggleFavorite
import android.Manifest
import android.app.Application
import android.os.Environment
import android.view.View
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.tabs.TabLayout
import com.infinity.comicreader.R
import com.infinity.comicreader.testing.idleUntil
import com.infinity.comicreader.data.FavoriteKind
import com.infinity.comicreader.data.FavoriteKindCache
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ListActivityFavoritesTest {

    private lateinit var context: Application
    private lateinit var root: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        for (name in listOf("ui", "library", "favorite_kinds")) context.getSharedPreferences(name, 0).edit().clear().commit()
        root = Environment.getExternalStorageDirectory()
        root.mkdirs()
    }

    /** True once the screen waits on nothing but the test: listed, favorites classified, facts read, presence asked, its menu looked into. */
    private fun ListActivity.quiet() =
        listJob?.isCompleted == true && listOf(classifyJob, factsJob, presenceJob, moreLook).none { it?.isCompleted == false }

    /** Runs the main looper until the screen is quiet. */
    private fun ListActivity.settle() = idleUntil("the library screen quiet") { quiet() }

    private fun folder(path: String, vararg files: String): File {
        val dir = File(root, path)
        dir.mkdirs()
        files.forEach { File(dir, it).createNewFile() }
        return dir
    }

    /** Text of every row the list shows, headers included. */
    private fun rows(recycler: RecyclerView): List<String> {
        recycler.measure(0, 0)
        recycler.layout(0, 0, 1000, 20000)
        return (0 until recycler.childCount).map { i ->
            when (val title = recycler.getChildAt(i).findViewById<View>(R.id.title)) {
                is TitleView -> title.text.toString()
                else -> (title as TextView).text.toString()
            }
        }
    }

    /** No work of this test's screens runs on into the next. */
    @After
    fun awaitLeftovers() = awaitLeftoverWork()

    @Test
    fun favoritesAreGroupedAndLabeled() {
        val library = Library(context)
        val series = folder("stacks/Comics", "Issue 1.cbz", "Issue 2.cbz")
        val pages = folder("books/Comics", *(1..6).map { "p$it.jpg" }.toTypedArray())
        val shelf = folder("Shelf")
        folder("Shelf/A", "a1.cbz")
        folder("Shelf/B", "b1.cbz")
        listOf(series, pages, shelf).forEach { library.toggleFavorite(it) }
        Prefs(context).lastPath = root.absolutePath

        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        activity.settle()
        activity.findViewById<TabLayout>(R.id.tabs).getTabAt(1)!!.select()
        activity.settle()
        val recycler = activity.findViewById<RecyclerView>(R.id.recycler)
        assertEquals(
            listOf("Libraries", "Shelf", "Sets", "stacks/Comics", "Comics", "books/Comics"),
            rows(recycler)
        )
    }

    @Test
    fun ungroupedFavoritesAreOneList() {
        val library = Library(context)
        library.toggleFavorite(folder("Solo", "x.cbz"))
        Prefs(context).groupFavorites = false
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        activity.settle()
        activity.findViewById<TabLayout>(R.id.tabs).getTabAt(1)!!.select()
        activity.settle()
        assertEquals(listOf("Solo"), rows(activity.findViewById(R.id.recycler)))
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp")
    fun gridRowsFitBetweenSectionHeaders() {
        val library = Library(context)
        library.toggleFavorite(folder("Solo", "x.cbz"))
        library.toggleFavorite(File(root, "a.cbz").apply { createNewFile() })
        val prefs = Prefs(context)
        prefs.setGridSize(Prefs.TAB_FAVORITES, 2, 2)
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        activity.settle()
        activity.findViewById<TabLayout>(R.id.tabs).getTabAt(1)!!.select()
        activity.settle()
        val recycler = activity.findViewById<RecyclerView>(R.id.recycler)
        // Sets header, Solo, Comics header, a.cbz: both rows and both headers on screen.
        assertEquals(4, recycler.childCount)
        val bottom = (0 until recycler.childCount).maxOf { recycler.getChildAt(it).bottom }
        assertTrue(bottom <= recycler.height - recycler.paddingBottom)
        assertTrue((recycler.adapter as BookAdapter).cellHeight > 0)
    }

    @Test
    fun ungroupedFavoritesAreClassifiedOnceGrouped() {
        val library = Library(context)
        val solo = folder("Solo", "x.cbz")
        library.toggleFavorite(solo)
        val prefs = Prefs(context)
        prefs.groupFavorites = false
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        activity.settle()
        activity.findViewById<TabLayout>(R.id.tabs).getTabAt(1)!!.select()
        activity.settle()
        assertNull(FavoriteKindCache(context).get(solo.absolutePath, solo.lastModified()))

        prefs.groupFavorites = true
        activity.settle()
        assertEquals(FavoriteKind.SET, FavoriteKindCache(context).get(solo.absolutePath, solo.lastModified()))
        assertEquals(listOf("Sets", "Solo"), rows(activity.findViewById(R.id.recycler)))
    }
}
