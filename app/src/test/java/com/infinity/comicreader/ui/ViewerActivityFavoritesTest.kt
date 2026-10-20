package com.infinity.comicreader.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
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

/** The reader's menu favorites the open comic and the folder it is in. */
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
        ZipOutputStream(book.outputStream()).use { zip ->
            repeat(3) {
                zip.putNextEntry(ZipEntry("p%02d.png".format(it)))
                zip.write(byteArrayOf(0))
                zip.closeEntry()
            }
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun launch(): ViewerActivity {
        val intent = Intent(context, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_PATH, book.absolutePath)
        val viewer = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup().get()
        val progress = viewer.findViewById<View>(R.id.viewer_progress)
        val deadline = System.currentTimeMillis() + 5_000
        do {
            Thread.sleep(10)
            idle()
        } while (progress.visibility == View.VISIBLE && System.currentTimeMillis() < deadline)
        return viewer
    }

    /** The toolbar menu's title for [id], prepared again. */
    private fun ViewerActivity.title(id: Int): String {
        invalidateOptionsMenu()
        idle()
        return findViewById<Toolbar>(R.id.viewer_toolbar).menu.findItem(id).title.toString()
    }

    private fun ViewerActivity.click(id: Int) {
        shadowOf(this).clickMenuItem(id)
        idle()
    }

    @Test
    fun comicIsAddedAndRemoved() {
        val viewer = launch()
        assertEquals(context.getString(R.string.menu_add_favorite), viewer.title(R.id.menu_favorite))
        viewer.click(R.id.menu_favorite)
        assertTrue(library.isFavorite(book.absolutePath))
        assertFalse(library.isFavorite(book.parent!!))
        assertEquals(context.getString(R.string.menu_remove_favorite), viewer.title(R.id.menu_favorite))
        viewer.click(R.id.menu_favorite)
        assertFalse(library.isFavorite(book.absolutePath))
    }

    @Test
    fun folderIsAddedAndRemoved() {
        val viewer = launch()
        assertEquals(context.getString(R.string.menu_add_folder_favorite), viewer.title(R.id.menu_favorite_folder))
        viewer.click(R.id.menu_favorite_folder)
        assertTrue(library.isFavorite(book.parent!!))
        assertFalse(library.isFavorite(book.absolutePath))
        assertEquals(context.getString(R.string.menu_remove_folder_favorite), viewer.title(R.id.menu_favorite_folder))
        viewer.click(R.id.menu_favorite_folder)
        assertFalse(library.isFavorite(book.parent!!))
    }
}
