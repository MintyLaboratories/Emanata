package com.infinity.comicreader.ui

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

    private fun addHistory() =
        library.addHistory(HistoryItem("/books/attic/one.cbz", "one.cbz", 7, 20, 1L))

    @Test
    fun clearHistoryWaitsForConfirmation() {
        addHistory()
        tap("pref_clear_history").press(DialogInterface.BUTTON_NEGATIVE)
        assertEquals(1, library.history().size)
        tap("pref_clear_history").press(DialogInterface.BUTTON_POSITIVE)
        assertTrue(library.history().isEmpty())
    }

    @Test
    fun clearBookmarksWaitsForConfirmation() {
        library.addBookmark(Bookmark("/books/attic/one.cbz", "one.cbz", 3, "", 1L))
        tap("pref_clear_bookmarks").press(DialogInterface.BUTTON_NEGATIVE)
        assertEquals(1, library.bookmarks().size)
        tap("pref_clear_bookmarks").press(DialogInterface.BUTTON_POSITIVE)
        assertTrue(library.bookmarks().isEmpty())
    }

    @Test
    fun clearThumbnailsWaitsForConfirmation() {
        val thumb = File(context.cacheDir, "thumbnails/a.png").apply { parentFile!!.mkdirs(); writeText("x") }
        tap("pref_clear_thumbs").press(DialogInterface.BUTTON_NEGATIVE)
        assertTrue(thumb.exists())
        tap("pref_clear_thumbs").press(DialogInterface.BUTTON_POSITIVE)
        awaitUntil { !thumb.exists() && ShadowToast.getTextOfLatestToast() != null }
        assertFalse(thumb.exists())
        assertEquals(context.getString(R.string.pref_cleared_thumbs), ShadowToast.getTextOfLatestToast())
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
        library.setAdjustments("/books/attic/one.cbz", Adjustments(10, -5, 20))
        addHistory()

        val cancelled = tap("pref_reset")
        assertTrue(cancelled.message().contains("Per-book adjustments and reading progress are kept"))
        cancelled.press(DialogInterface.BUTTON_NEGATIVE)
        assertEquals("sepia", prefs.filter)

        tap("pref_reset").press(DialogInterface.BUTTON_POSITIVE)
        assertEquals("none", prefs.filter)
        assertEquals(Adjustments(10, -5, 20), library.adjustmentsFor("/books/attic/one.cbz"))
        assertEquals(7, library.pageFor("/books/attic/one.cbz"))
    }
}
