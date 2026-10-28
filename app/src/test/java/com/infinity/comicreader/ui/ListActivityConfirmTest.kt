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
import com.infinity.comicreader.data.HistoryItem
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
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

    /** Runs the main looper until [done] holds, for work that hops to a background thread. */
    private fun awaitUntil(done: () -> Boolean = { false }) {
        repeat(25) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            Thread.sleep(20)
        }
    }

    private fun launch(): ListActivity =
        Robolectric.buildActivity(ListActivity::class.java).setup().get().also { awaitUntil() }

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

    @Test
    fun resetStatisticsWaitsForConfirmation() {
        library.addReading(60, 5)
        val activity = launch()

        activity.menu(R.id.menu_stats).press(DialogInterface.BUTTON_NEUTRAL)
        val canceled = latestDialog()
        assertEquals(context.getString(R.string.confirm_reset_stats_msg), canceled.message())
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

        activity.menu(R.id.menu_mark_all_unread).press(DialogInterface.BUTTON_NEGATIVE)
        awaitUntil()
        assertTrue(library.isRead(bookId(book)))

        activity.menu(R.id.menu_mark_all_unread).press(DialogInterface.BUTTON_POSITIVE)
        awaitUntil { !library.isRead(bookId(book)) }
        assertFalse(library.isRead(bookId(book)))
    }

    @Test
    fun markAllReadWaitsForConfirmation() {
        val activity = launch()

        activity.menu(R.id.menu_mark_all_read).press(DialogInterface.BUTTON_NEGATIVE)
        awaitUntil()
        assertFalse(library.isRead(bookId(book)))

        activity.menu(R.id.menu_mark_all_read).press(DialogInterface.BUTTON_POSITIVE)
        awaitUntil { library.isRead(bookId(book)) }
        assertTrue(library.isRead(bookId(book)))
    }

    /** Long-presses the first History row and picks Clear history from its menu. */
    private fun ListActivity.clearHistoryFromRow(): AlertDialog {
        val recycler = findViewById<RecyclerView>(R.id.recycler)
        recycler.measure(0, 0)
        recycler.layout(0, 0, 1000, 2000)
        recycler.findViewHolderForAdapterPosition(0)!!.itemView.performLongClick()
        shadowOf(Looper.getMainLooper()).idle()
        val menu = latestDialog()
        val list = menu.listView
        val label = context.getString(R.string.pref_clear_history)
        val index = (0 until list.adapter.count).first { list.adapter.getItem(it).toString() == label }
        list.performItemClick(list.adapter.getView(index, null, list), index, index.toLong())
        shadowOf(Looper.getMainLooper()).idle()
        return latestDialog()
    }

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
