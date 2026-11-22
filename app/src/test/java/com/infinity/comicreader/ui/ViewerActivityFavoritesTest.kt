package com.infinity.comicreader.ui

import com.infinity.comicreader.testing.idleUntil
import com.infinity.comicreader.testing.toggleFavorite
import com.infinity.comicreader.testing.bookId
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Library
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The reader's Favorites ▸ this comic and this folder, each checked while it is a favorite. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerActivityFavoritesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Application
    private lateinit var library: Library
    private lateinit var book: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        library = Library(context)
        book = File(tmp.newFolder("attic"), "one.cbz")
        write(book)
    }

    private fun write(f: File) {
        ZipOutputStream(f.outputStream()).use { zip ->
            repeat(3) {
                zip.putNextEntry(ZipEntry("p%02d.png".format(it)))
                zip.write(byteArrayOf(0))
                zip.closeEntry()
            }
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun launch(file: File = book): ViewerActivity {
        val intent = Intent(context, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_PATH, file.absolutePath)
        val viewer = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup().get()
        val progress = viewer.findViewById<View>(R.id.viewer_progress)
        idleUntil("the book open") { progress.visibility != View.VISIBLE }
        return viewer
    }

    /** The toolbar menu's item [id], prepared again. */
    private fun ViewerActivity.item(id: Int): MenuItem {
        invalidateOptionsMenu()
        idle()
        return findViewById<Toolbar>(R.id.viewer_toolbar).menu.findItem(id)
    }

    private fun ViewerActivity.checked(id: Int): Boolean = item(id).isChecked

    private fun ViewerActivity.click(id: Int) {
        shadowOf(this).clickMenuItem(id)
        idle()
    }

    @Test
    fun comicIsAddedAndRemoved() {
        val viewer = launch()
        assertEquals(context.getString(R.string.favorite_this_comic), viewer.item(R.id.menu_favorite).title.toString())
        assertFalse(viewer.checked(R.id.menu_favorite))
        viewer.click(R.id.menu_favorite)
        assertTrue(library.isFavorite(bookId(book)))
        assertFalse(library.isFavoriteFolder(book.parent!!))
        assertTrue(viewer.checked(R.id.menu_favorite))
        viewer.click(R.id.menu_favorite)
        assertFalse(library.isFavorite(bookId(book)))
    }

    @Test
    fun aCopyInTheCacheHasNoFavoriteItems() {
        val copy = File(File(context.cacheDir, "shared").apply { mkdirs() }, "two.cbz")
        write(copy)
        try {
            val viewer = launch(copy)
            assertFalse(viewer.item(R.id.menu_favorites).isVisible)
            assertFalse(viewer.item(R.id.menu_favorite).isVisible)
            assertFalse(viewer.item(R.id.menu_favorite_folder).isVisible)
        } finally {
            copy.parentFile!!.deleteRecursively()
        }
    }

    @Test
    fun favoritesAddedElsewhereShowWhenTheComicOpens() {
        library.toggleFavorite(book)
        library.toggleFavoriteFolder(book.parent!!)
        val viewer = launch()
        assertTrue(viewer.checked(R.id.menu_favorite))
        assertTrue(viewer.checked(R.id.menu_favorite_folder))
    }

    @Test
    fun folderIsAddedAndRemoved() {
        val viewer = launch()
        assertFalse(viewer.checked(R.id.menu_favorite_folder))
        viewer.click(R.id.menu_favorite_folder)
        assertTrue(library.isFavoriteFolder(book.parent!!))
        assertFalse(library.isFavorite(bookId(book)))
        assertTrue(viewer.checked(R.id.menu_favorite_folder))
        viewer.click(R.id.menu_favorite_folder)
        assertFalse(library.isFavoriteFolder(book.parent!!))
    }

    @Test
    fun favoritesClearedInSettingsShowClearedOnReturn() {
        library.toggleFavorite(book)
        library.toggleFavoriteFolder(book.parent!!)
        val intent = Intent(context, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_PATH, book.absolutePath)
        val c = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup()
        val progress = c.get().findViewById<View>(R.id.viewer_progress)
        idleUntil("the book open") { progress.visibility != View.VISIBLE }
        assertTrue(c.get().checked(R.id.menu_favorite))
        assertTrue(c.get().checked(R.id.menu_favorite_folder))
        // Settings on top of the reader.
        c.pause().stop()
        library.clearFavorites()
        c.start().resume()
        idle()
        assertFalse(c.get().checked(R.id.menu_favorite))
        assertFalse(c.get().checked(R.id.menu_favorite_folder))
        c.pause().stop().destroy()
    }
}
