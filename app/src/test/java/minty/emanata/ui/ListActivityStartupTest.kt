package minty.emanata.ui

import minty.emanata.testing.toggleFavorite
import android.Manifest
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.tabs.TabLayout
import minty.emanata.R
import minty.emanata.testing.idleUntil
import minty.emanata.data.Library
import minty.emanata.data.Prefs
import minty.emanata.data.StartupLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** True once the screen waits on nothing but the test: listed, favorites classified, facts read, presence asked, its menu looked into. */
    private fun ListActivity.quiet() =
        listJob?.isCompleted == true && listOf(classifyJob, factsJob, presenceJob, moreLook).none { it?.isCompleted == false }

    /** Runs the main looper until the screen is quiet. */
    private fun ListActivity.settle() = idleUntil("the library screen quiet") { quiet() }

    private fun ListActivity.tab() = findViewById<TabLayout>(R.id.tabs).selectedTabPosition

    private fun ListActivity.crumbPaths() = findViewById<CrumbBar>(R.id.pathbar).crumbs.map { it.path }

    /** No work of this test's screens runs on into the next. */
    @After
    fun awaitLeftovers() = awaitLeftoverWork()

    @Test
    fun opensOnBrowseByDefault() {
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        activity.settle()
        assertEquals(0, activity.tab())
    }

    @Test
    fun opensOnTheStartupTabWithBrowseBehindIt() {
        val dir = File(root, "Comics").apply { mkdirs() }
        prefs.lastPath = dir.absolutePath
        prefs.startupTab = Prefs.TAB_HISTORY
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        activity.settle()
        assertEquals(2, activity.tab())
        activity.findViewById<TabLayout>(R.id.tabs).getTabAt(0)!!.select()
        activity.settle()
        assertEquals(dir.absolutePath, activity.crumbPaths().last())
    }

    @Test
    fun browseRefreshesOnResumeAfterStartingOnAnotherTab() {
        val dir = File(root, "Comics").apply { mkdirs() }
        prefs.lastPath = dir.absolutePath
        prefs.startupTab = Prefs.TAB_HISTORY
        val controller = Robolectric.buildActivity(ListActivity::class.java).setup()
        controller.get().settle()
        controller.get().findViewById<TabLayout>(R.id.tabs).getTabAt(0)!!.select()
        controller.get().settle()
        File(dir, "new.cbz").createNewFile()
        controller.pause().stop().restart().start().resume()
        controller.get().settle()
        val adapter = controller.get().findViewById<RecyclerView>(R.id.recycler).adapter as BookAdapter
        assertTrue(adapter.positionOf(File(dir, "new.cbz").absolutePath) >= 0)
    }

    @Test
    fun browseOpensAtTheCustomLocation() {
        prefs.lastPath = File(root, "Last").apply { mkdirs() }.absolutePath
        val custom = File(root, "Custom").apply { mkdirs() }
        prefs.setStartupFolder(custom.absolutePath)
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        activity.settle()
        assertEquals(custom.absolutePath, activity.crumbPaths().last())
    }

    @Test
    fun browseOpensAtTheFirstFavoriteFolder() {
        prefs.lastPath = File(root, "Last").apply { mkdirs() }.absolutePath
        val library = Library(ApplicationProvider.getApplicationContext())
        val book = File(root, "a.cbz").apply { createNewFile() }
        val beta = File(root, "Beta").apply { mkdirs() }
        val alpha = File(root, "Alpha").apply { mkdirs() }
        listOf(book, beta, alpha).forEach { library.toggleFavorite(it) }
        prefs.groupFavorites = false
        prefs.startupLocation = StartupLocation.FIRST_FAVORITE
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        activity.settle()
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
        activity.settle()
        assertEquals(0, activity.tab())
        assertEquals(incoming.absolutePath, activity.crumbPaths().last())
    }

    @Test
    fun aFolderFromAnotherAppOpensByItsCanonicalPath() {
        val incoming = File(root, "Incoming").apply { mkdirs() }
        File(root, "Other").mkdirs()
        val path = File(root, "Other/../Incoming").path
        val activity = Robolectric.buildActivity(ListActivity::class.java, viewIntent(path)).setup().get()
        activity.settle()
        assertEquals(listOf(root.absolutePath, incoming.absolutePath), activity.crumbPaths())
    }

    @Test
    fun aBookFromAnotherAppOpensOnceAcrossARecreate() {
        val book = File(root, "a.cbz").apply { createNewFile() }
        val first = Robolectric.buildActivity(ListActivity::class.java, viewIntent(book.absolutePath)).setup()
        first.get().settle()
        assertEquals(ViewerActivity::class.java.name, shadowOf(first.get()).nextStartedActivity?.component?.className)
        val state = Bundle()
        first.saveInstanceState(state)
        val second = Robolectric.buildActivity(ListActivity::class.java, viewIntent(book.absolutePath)).setup(state)
        second.get().settle()
        assertNull(shadowOf(second.get()).nextStartedActivity)
    }

    @Test
    fun aBookThisAppSharedOpensWhereItIsAndOnlyFromStorage() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val book = File(root, "b.cbz").apply { writeBytes(ByteArray(10)) }
        val own = Intent(Intent.ACTION_VIEW, minty.emanata.files.BookUris.of(context, book.absolutePath))
        val activity = Robolectric.buildActivity(ListActivity::class.java, own).setup().get()
        activity.settle()
        assertEquals(ViewerActivity::class.java.name, shadowOf(activity).nextStartedActivity?.component?.className)
        // Never copied in, as other apps' content is.
        assertFalse(File(context.cacheDir, "incoming").exists())

        val private = File(context.filesDir, "c.cbz").apply { writeBytes(ByteArray(10)) }
        val issued = Intent(Intent.ACTION_VIEW, minty.emanata.files.BookUris.of(context, private.absolutePath))
        val other = Robolectric.buildActivity(ListActivity::class.java, issued).setup().get()
        other.settle()
        assertNull(shadowOf(other).nextStartedActivity)

        // A URI of ours this app never issued opens nothing, even for a book in storage.
        val made = android.net.Uri.parse("content://${minty.emanata.files.BookUris.authority(context.packageName)}/AAAAAAAAAAAAAAAAAAAAAA/b.cbz")
        val third = Robolectric.buildActivity(ListActivity::class.java, Intent(Intent.ACTION_VIEW, made)).setup().get()
        third.settle()
        assertNull(shadowOf(third).nextStartedActivity)
        assertFalse(File(context.cacheDir, "incoming").exists())
    }

    @Test
    fun aRecreateKeepsTheTabAndFolder() {
        val dir = File(root, "Kept").apply { mkdirs() }
        prefs.lastPath = dir.absolutePath
        prefs.startupTab = Prefs.TAB_FAVORITES
        val first = Robolectric.buildActivity(ListActivity::class.java).setup()
        first.get().settle()
        first.get().findViewById<TabLayout>(R.id.tabs).getTabAt(3)!!.select()
        first.get().settle()
        val state = Bundle()
        first.saveInstanceState(state)
        val second = Robolectric.buildActivity(ListActivity::class.java).setup(state)
        second.get().settle()
        assertEquals(3, second.get().tab())
    }
}
