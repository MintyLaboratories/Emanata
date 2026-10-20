package com.infinity.comicreader.ui

import android.Manifest
import android.app.Application
import android.os.Environment
import android.os.Looper
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.tabs.TabLayout
import com.infinity.comicreader.R
import com.infinity.comicreader.data.FavoriteKind
import com.infinity.comicreader.data.FavoriteKindCache
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun idle() {
        // Background classification runs on the IO pool; let it land on the main thread.
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

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
            recycler.getChildAt(i).findViewById<TextView>(R.id.title).text.toString()
        }
    }

    @Test
    fun favoritesAreGroupedAndLabeled() {
        val library = Library(context)
        val series = folder("stacks/Comics", "Issue 1.cbz", "Issue 2.cbz")
        val pages = folder("books/Comics", *(1..6).map { "p$it.jpg" }.toTypedArray())
        val shelf = folder("Shelf")
        folder("Shelf/A", "a1.cbz")
        folder("Shelf/B", "b1.cbz")
        listOf(series, pages, shelf).forEach { library.toggleFavorite(it.absolutePath) }
        Prefs(context).lastPath = root.absolutePath

        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        activity.findViewById<TabLayout>(R.id.tabs).getTabAt(1)!!.select()
        idle()
        val recycler = activity.findViewById<RecyclerView>(R.id.recycler)
        assertEquals(
            listOf("Libraries", "Shelf", "Sets", "stacks/Comics", "Comics", "books/Comics"),
            rows(recycler)
        )
    }

    @Test
    fun ungroupedFavoritesAreOneList() {
        val library = Library(context)
        library.toggleFavorite(folder("Solo", "x.cbz").absolutePath)
        Prefs(context).groupFavorites = false
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        activity.findViewById<TabLayout>(R.id.tabs).getTabAt(1)!!.select()
        idle()
        assertEquals(listOf("Solo"), rows(activity.findViewById(R.id.recycler)))
    }

    @Test
    fun ungroupedFavoritesAreClassifiedOnceGrouped() {
        val library = Library(context)
        val solo = folder("Solo", "x.cbz")
        library.toggleFavorite(solo.absolutePath)
        val prefs = Prefs(context)
        prefs.groupFavorites = false
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        activity.findViewById<TabLayout>(R.id.tabs).getTabAt(1)!!.select()
        idle()
        assertNull(FavoriteKindCache(context).get(solo.absolutePath, solo.lastModified()))

        prefs.groupFavorites = true
        idle()
        assertEquals(FavoriteKind.SET, FavoriteKindCache(context).get(solo.absolutePath, solo.lastModified()))
        assertEquals(listOf("Sets", "Solo"), rows(activity.findViewById(R.id.recycler)))
    }
}
