package com.infinity.comicreader.ui

import com.infinity.comicreader.data.BookRef
import com.infinity.comicreader.data.BookId
import android.app.Application
import android.content.Context
import android.content.DialogInterface
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Bookmark
import com.infinity.comicreader.data.HistoryItem
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.reader.Adjustments
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
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.File

/** Every Clear / Reset in Settings asks first and acts only when confirmed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsConfirmTest {

    private lateinit var context: Context
    private lateinit var library: Library
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        library = Library(context)
        prefs = Prefs(context)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Taps [key] on a fresh settings screen and returns the dialog it opened. */
    private fun tap(key: String): AlertDialog {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        idle()
        val fragment = activity.supportFragmentManager
            .findFragmentById(R.id.settings_container) as PreferenceFragmentCompat
        val pref = fragment.findPreference<Preference>(key)!!
        pref.onPreferenceClickListener!!.onPreferenceClick(pref)
        idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(dialog.isShowing)
        return dialog
    }

    private fun AlertDialog.message() = findViewById<TextView>(android.R.id.message)!!.text.toString()

    private fun AlertDialog.press(which: Int) {
        getButton(which).performClick()
        idle()
    }

    /** A book as the library keeps it. */
    private val one = BookRef(BookId(1), "/books/attic/one.cbz")

    private fun addHistory() =
        library.addHistory(HistoryItem(one.path, "one.cbz", 7, 20, 1L, one.id))

    @Test
    fun clearHistoryWaitsForConfirmation() {
        addHistory()
        tap("pref_clear_history").press(DialogInterface.BUTTON_NEGATIVE)
        assertEquals(1, library.history().size)
        tap("pref_clear_history").press(DialogInterface.BUTTON_POSITIVE)
        assertTrue(library.history().isEmpty())
    }

    @Test
    fun clearFavoritesWaitsForConfirmation() {
        library.setFavorite(one, true)
        library.toggleFavoriteFolder("/books/attic")
        tap("pref_clear_favorites").press(DialogInterface.BUTTON_NEGATIVE)
        assertEquals(2, library.favoriteItems().size)
        tap("pref_clear_favorites").press(DialogInterface.BUTTON_POSITIVE)
        assertTrue(library.favoriteItems().isEmpty())
    }

    @Test
    fun clearBookmarksWaitsForConfirmation() {
        library.addBookmark(Bookmark(one.path, "one.cbz", 3, "", 1L, one.id))
        tap("pref_clear_bookmarks").press(DialogInterface.BUTTON_NEGATIVE)
        assertEquals(1, library.bookmarks().size)
        tap("pref_clear_bookmarks").press(DialogInterface.BUTTON_POSITIVE)
        assertTrue(library.bookmarks().isEmpty())
    }

    /** Taps [key] and waits for the dialog it opens after work off the main thread. */
    private fun tapLater(key: String): AlertDialog {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        idle()
        val fragment = activity.supportFragmentManager
            .findFragmentById(R.id.settings_container) as PreferenceFragmentCompat
        val pref = fragment.findPreference<Preference>(key)!!
        val before = ShadowDialog.getLatestDialog()
        pref.onPreferenceClickListener!!.onPreferenceClick(pref)
        awaitUntil { ShadowDialog.getLatestDialog() !== before }
        return ShadowDialog.getLatestDialog() as AlertDialog
    }

    @Test
    fun clearCacheSaysWhatItFreesAndWaitsForConfirmation() {
        val thumb = File(context.cacheDir, "covers/a.png").apply { parentFile!!.mkdirs(); writeText("x") }
        val sizes = File(context.cacheDir, "page-sizes-by-id/0000000000000001-a.bin").apply { parentFile!!.mkdirs(); writeText("x") }
        library.setRead(one, true)
        val asked = tapLater("pref_clear_cache")
        assertTrue(asked.message().contains("kB"))
        asked.press(DialogInterface.BUTTON_NEGATIVE)
        assertTrue(thumb.exists())
        tapLater("pref_clear_cache").press(DialogInterface.BUTTON_POSITIVE)
        awaitUntil { !thumb.exists() && ShadowToast.getTextOfLatestToast() != null }
        assertFalse(thumb.exists())
        assertFalse(sizes.exists())
        assertEquals(context.getString(R.string.pref_cleared_cache), ShadowToast.getTextOfLatestToast())
        // Library data is not cache.
        assertTrue(library.isRead(one.id))
    }

    @Test
    fun clearLibraryDataNeedsItsBoxTickedAndKeepsStatisticsAndSettings() {
        prefs.filter = "sepia"
        library.setRead(one, true)
        library.addBookmark(Bookmark(one.path, "one.cbz", 3, "", 1L, one.id))
        library.setFolderLayout("/books/attic", com.infinity.comicreader.data.FolderLayout.Direction("rtl"))
        library.setFolderTallStrip("/books/strip", true)
        library.addReading(60, 4)
        val asked = tap("pref_clear_library")
        assertTrue(asked.message().contains("everything kept about your books and folders"))
        val clear = asked.getButton(DialogInterface.BUTTON_POSITIVE)
        assertFalse(clear.isEnabled)
        asked.findViewById<android.widget.CheckBox>(R.id.dialog_check)!!.performClick()
        assertTrue(clear.isEnabled)
        asked.press(DialogInterface.BUTTON_POSITIVE)
        awaitUntil { ShadowToast.getTextOfLatestToast() != null }
        assertFalse(library.isRead(one.id))
        assertTrue(library.bookmarks().isEmpty())
        assertNull(library.folderLayout("/books/attic"))
        assertNull(library.folderTallStrip("/books/strip"))
        assertEquals(60, library.stats().seconds)
        assertEquals("sepia", prefs.filter)
    }

    @Test
    fun clearReadingStatisticsWaitsForConfirmationAndKeepsReadMarks() {
        library.setRead(one, true)
        library.addReading(60, 4)
        val canceled = tap("pref_clear_stats")
        assertEquals(context.getString(R.string.confirm_clear_stats_title), canceled.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)!!.text.toString())
        canceled.press(DialogInterface.BUTTON_NEGATIVE)
        assertEquals(60, library.stats().seconds)
        tap("pref_clear_stats").press(DialogInterface.BUTTON_POSITIVE)
        assertEquals(0, library.stats().seconds)
        assertEquals(0, library.stats().pages)
        assertTrue(library.isRead(one.id))
    }

    @Test
    fun clearLibraryDataCanExportFirst() {
        library.setRead(one, true)
        val asked = tap("pref_clear_library")
        assertEquals(context.getString(R.string.confirm_clear_library_export), asked.getButton(DialogInterface.BUTTON_NEUTRAL).text.toString())
        asked.press(DialogInterface.BUTTON_NEGATIVE)
        assertTrue(library.isRead(one.id))
    }

    /** Runs the main looper until [done] holds, for work that hops to a background thread. */
    private fun awaitUntil(done: () -> Boolean) {
        repeat(100) {
            idle()
            if (done()) return
            Thread.sleep(20)
        }
    }

    @Test
    fun resetAllSettingsKeepsAdjustmentsAndProgress() {
        prefs.filter = "sepia"
        library.setAdjustments(one, Adjustments(10, -5, 20))
        addHistory()

        val canceled = tap("pref_reset")
        assertTrue(canceled.message().contains("Per-book adjustments and reading progress are kept"))
        canceled.press(DialogInterface.BUTTON_NEGATIVE)
        assertEquals("sepia", prefs.filter)

        tap("pref_reset").press(DialogInterface.BUTTON_POSITIVE)
        assertEquals("none", prefs.filter)
        assertEquals(Adjustments(10, -5, 20), library.adjustmentsFor(one.id))
        assertEquals(7, library.pageFor(one.id))
    }
}
