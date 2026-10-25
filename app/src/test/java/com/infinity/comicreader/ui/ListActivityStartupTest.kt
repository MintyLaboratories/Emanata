package com.infinity.comicreader.ui

import android.Manifest
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Looper
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.tabs.TabLayout
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.StartupLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private fun idle() {
        // Listings run on the IO pool; let them land on the main thread.
        repeat(10) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

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
    fun browseRefreshesOnResumeAfterStartingOnAnotherTab() {
        val dir = File(root, "Comics").apply { mkdirs() }
        prefs.lastPath = dir.absolutePath
        prefs.startupTab = Prefs.TAB_HISTORY
        val controller = Robolectric.buildActivity(ListActivity::class.java).setup()
        idle()
        controller.get().findViewById<TabLayout>(R.id.tabs).getTabAt(0)!!.select()
        idle()
        File(dir, "new.cbz").createNewFile()
        controller.pause().stop().restart().start().resume()
        idle()
        val adapter = controller.get().findViewById<RecyclerView>(R.id.recycler).adapter as BookAdapter
        assertTrue(adapter.positionOf(File(dir, "new.cbz").absolutePath) >= 0)
    }

    @Test
    fun browseOpensAtTheCustomLocation() {
        prefs.lastPath = File(root, "Last").apply { mkdirs() }.absolutePath
        val custom = File(root, "Custom").apply { mkdirs() }
        prefs.setStartupFolder(custom.absolutePath)
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        assertEquals(custom.absolutePath, activity.crumbPaths().last())
    }

    @Test
    fun browseOpensAtTheFirstFavoriteFolder() {
        prefs.lastPath = File(root, "Last").apply { mkdirs() }.absolutePath
        val library = Library(ApplicationProvider.getApplicationContext())
        val book = File(root, "a.cbz").apply { createNewFile() }
        val beta = File(root, "Beta").apply { mkdirs() }
        val alpha = File(root, "Alpha").apply { mkdirs() }
        listOf(book, beta, alpha).forEach { library.toggleFavorite(it.absolutePath) }
        prefs.groupFavorites = false
        prefs.startupLocation = StartupLocation.FIRST_FAVORITE
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        // Favorites sort by name, folders first.
        assertEquals(alpha.absolutePath, activity.crumbPaths().last())
    }

    private fun viewIntent(path: String) =
        Intent(Intent.ACTION_VIEW, Uri.Builder().scheme("file").path(path).build())

    @Test
    fun aFolderFromAnotherAppWinsOverTheStartupLocation() {
        prefs.setStartupFolder(File(root, "Custom").apply { mkdirs() }.absolutePath)
        prefs.startupTab = Prefs.TAB_FAVORITES
        val incoming = File(root, "Incoming").apply { mkdirs() }
        val activity = Robolectric.buildActivity(ListActivity::class.java, viewIntent(incoming.absolutePath)).setup().get()
        idle()
        assertEquals(0, activity.tab())
        assertEquals(incoming.absolutePath, activity.crumbPaths().last())
    }

    @Test
    fun aFolderFromAnotherAppOpensByItsCanonicalPath() {
        val incoming = File(root, "Incoming").apply { mkdirs() }
        File(root, "Other").mkdirs()
        val path = File(root, "Other/../Incoming").path
        val activity = Robolectric.buildActivity(ListActivity::class.java, viewIntent(path)).setup().get()
        idle()
        assertEquals(listOf(root.absolutePath, incoming.absolutePath), activity.crumbPaths())
    }

    @Test
    fun aBookFromAnotherAppOpensOnceAcrossARecreate() {
        val book = File(root, "a.cbz").apply { createNewFile() }
        val first = Robolectric.buildActivity(ListActivity::class.java, viewIntent(book.absolutePath)).setup()
        idle()
        assertEquals(ViewerActivity::class.java.name, shadowOf(first.get()).nextStartedActivity?.component?.className)
        val state = Bundle()
        first.saveInstanceState(state)
        val second = Robolectric.buildActivity(ListActivity::class.java, viewIntent(book.absolutePath)).setup(state)
        idle()
        assertNull(shadowOf(second.get()).nextStartedActivity)
    }

    @Test
    fun aBookThisAppSharedOpensWhereItIsAndOnlyFromStorage() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val book = File(root, "b.cbz").apply { writeBytes(ByteArray(10)) }
        val own = Intent(Intent.ACTION_VIEW, com.infinity.comicreader.files.BookUris.of(context.packageName, book.absolutePath))
        val activity = Robolectric.buildActivity(ListActivity::class.java, own).setup().get()
        idle()
        assertEquals(ViewerActivity::class.java.name, shadowOf(activity).nextStartedActivity?.component?.className)
        // Never copied in, as other apps' content is.
        assertFalse(File(context.cacheDir, "incoming").exists())

        val private = File(context.filesDir, "c.cbz").apply { writeBytes(ByteArray(10)) }
        val crafted = Intent(Intent.ACTION_VIEW, com.infinity.comicreader.files.BookUris.of(context.packageName, private.absolutePath))
        val other = Robolectric.buildActivity(ListActivity::class.java, crafted).setup().get()
        idle()
        assertNull(shadowOf(other).nextStartedActivity)
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
