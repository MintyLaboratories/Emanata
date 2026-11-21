package com.infinity.comicreader.ui

import com.infinity.comicreader.testing.ref
import com.infinity.comicreader.testing.bookId
import android.Manifest
import android.app.Application
import android.content.DialogInterface
import android.os.Environment
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.testing.idleUntil
import com.infinity.comicreader.data.HistoryItem
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
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
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** The library's Clear / Reset actions ask first and act only when confirmed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ListActivityConfirmTest {

    private lateinit var context: Application
    private lateinit var prefs: Prefs
    private lateinit var library: Library
    private lateinit var dir: File
    private lateinit var book: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        for (name in listOf("ui", "library", "favorite_kinds")) context.getSharedPreferences(name, 0).edit().clear().commit()
        prefs = Prefs(context)
        library = Library(context)
        dir = File(Environment.getExternalStorageDirectory(), "books").apply { mkdirs() }
        book = File(dir, "one.cbz").apply { createNewFile() }
        prefs.lastPath = dir.absolutePath
    }

    /** Starts the library and waits until its folder is listed. */
    private fun launch(): ListActivity =
        Robolectric.buildActivity(ListActivity::class.java).setup().get().also { a -> idleUntil("the folder listed") { a.listJob?.isCompleted == true } }

    private fun latestDialog() = ShadowDialog.getLatestDialog() as AlertDialog

    private fun AlertDialog.message() = findViewById<TextView>(android.R.id.message)!!.text.toString()

    private fun AlertDialog.press(which: Int) {
        getButton(which).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun ListActivity.menu(id: Int): AlertDialog {
        shadowOf(this).clickMenuItem(id)
        shadowOf(Looper.getMainLooper()).idle()
        return latestDialog()
    }

    /** No work of this test's screens runs on into the next. */
    @After
    fun awaitLeftovers() = awaitLeftoverWork()

    @Test
    fun clearStatisticsWaitsForConfirmation() {
        library.addReading(60, 5)
        val activity = launch()

        activity.menu(R.id.menu_stats).press(DialogInterface.BUTTON_NEUTRAL)
        val canceled = latestDialog()
        assertEquals(context.getString(R.string.confirm_clear_stats_msg), canceled.message())
        canceled.press(DialogInterface.BUTTON_NEGATIVE)
        assertEquals(5L, library.stats().pages.toLong())

        activity.menu(R.id.menu_stats).press(DialogInterface.BUTTON_NEUTRAL)
        latestDialog().press(DialogInterface.BUTTON_POSITIVE)
        assertEquals(0L, library.stats().pages.toLong())
    }

    @Test
    fun markAllUnreadWaitsForConfirmation() {
        library.setReadMany(listOf(ref(book)), true)
        val activity = launch()

        // Canceled: nothing was started.
        activity.menu(R.id.menu_mark_all_unread).press(DialogInterface.BUTTON_NEGATIVE)
        assertNull(activity.markJob)
        assertTrue(library.isRead(bookId(book)))

        activity.menu(R.id.menu_mark_all_unread).press(DialogInterface.BUTTON_POSITIVE)
        // The marking runs on the main thread between its I/O steps.
        idleUntil("marking unread") { activity.markJob!!.isCompleted }
        assertFalse(library.isRead(bookId(book)))
    }

    @Test
    fun markAllReadWaitsForConfirmation() {
        val activity = launch()

        // Canceled: nothing was started.
        activity.menu(R.id.menu_mark_all_read).press(DialogInterface.BUTTON_NEGATIVE)
        assertNull(activity.markJob)
        assertFalse(library.isRead(bookId(book)))

        activity.menu(R.id.menu_mark_all_read).press(DialogInterface.BUTTON_POSITIVE)
        idleUntil("marking read") { activity.markJob!!.isCompleted }
        assertTrue(library.isRead(bookId(book)))
    }

    /** Picks Clear history from the History tab's menu. */
    private fun ListActivity.clearHistoryFromRow(): AlertDialog = menu(R.id.menu_clear_all)

    @Test
    fun clearHistoryFromTheHistoryTabWaitsForConfirmation() {
        library.addHistory(HistoryItem(book.absolutePath, book.name, 2, 10, 1L, bookId(book)))
        prefs.startupTab = Prefs.TAB_HISTORY
        val activity = launch()

        val canceled = activity.clearHistoryFromRow()
        assertEquals(context.getString(R.string.confirm_clear_history_msg), canceled.message())
        canceled.press(DialogInterface.BUTTON_NEGATIVE)
        assertEquals(1, library.history().size)

        activity.clearHistoryFromRow().press(DialogInterface.BUTTON_POSITIVE)
        assertTrue(library.history().isEmpty())
    }
}
