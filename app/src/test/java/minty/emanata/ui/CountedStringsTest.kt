package minty.emanata.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import minty.emanata.R
import minty.emanata.data.ReadingStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Counts read as one or many: "1 item", "2 items". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CountedStringsTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    private fun count(id: Int, n: Int, vararg more: Any): String = context.resources.getQuantityString(id, n, n, *more)

    @Test
    fun listRowsCountOneOrMany() {
        assertEquals("1 item", count(R.plurals.books_count, 1))
        assertEquals("2 items", count(R.plurals.books_count, 2))
        assertEquals("0 items", count(R.plurals.books_count, 0))
        assertEquals("1 item · 1 read", count(R.plurals.folder_read_summary, 1, 1))
        assertEquals("3 items · 1 read", count(R.plurals.folder_read_summary, 3, 1))
        assertEquals("1 page", count(R.plurals.pages_count, 1))
        assertEquals("24 pages", count(R.plurals.pages_count, 24))
    }

    @Test
    fun otherCountsToo() {
        assertEquals("Deleted 1 item", count(R.plurals.deleted_format, 1))
        assertEquals("Permanently delete 1 file?", count(R.plurals.delete_selected_msg, 1))
        assertEquals("Permanently delete 2 files?", count(R.plurals.delete_selected_msg, 2))
        assertEquals("2 identical copies · 5 MB each", count(R.plurals.dupe_group, 2, "5 MB"))
    }

    @Test
    fun streaksCountDays() {
        val text = context.statsText(ReadingStats(seconds = 3_660, pages = 1, books = 1, daysRead = 1, streak = 1, bestStreak = 2))
        assertTrue(text, text.contains("Current streak: 1 day\n"))
        assertTrue(text, text.endsWith("Best streak: 2 days"))
        assertTrue(text, text.contains("Time reading: 1 h 1 min"))
    }
}
