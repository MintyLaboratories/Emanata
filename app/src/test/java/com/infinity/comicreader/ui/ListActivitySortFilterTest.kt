package com.infinity.comicreader.ui

import android.Manifest
import android.app.Application
import android.os.Environment
import android.os.Looper
import android.view.View
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.chip.Chip
import com.infinity.comicreader.R
import com.infinity.comicreader.data.BookFactsCache
import com.infinity.comicreader.data.Bookmark
import com.infinity.comicreader.data.EntryType
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.LibraryFilter
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ReadingDirection
import com.infinity.comicreader.data.SortKey
import com.infinity.comicreader.data.SortLevel
import com.infinity.comicreader.data.TabSort
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
class ListActivitySortFilterTest {

    private lateinit var prefs: Prefs
    private lateinit var dir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("ui", 0).edit().clear().commit()
        prefs = Prefs(context)
        dir = File(Environment.getExternalStorageDirectory(), "attic/Series A").apply { mkdirs() }
        for (name in listOf("b (2010).cbz", "a (2001).cbz", "c.pdf")) File(dir, name).createNewFile()
        File(dir, "z extras").mkdirs()
        prefs.lastPath = dir.absolutePath
    }

    private fun idle() {
        repeat(10) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    /** Names shown, in order, read through the adapter's positions. */
    private fun ListActivity.shown(): List<String> {
        val adapter = findViewById<RecyclerView>(R.id.recycler).adapter as BookAdapter
        val names = listOf("a (2001).cbz", "b (2010).cbz", "c.pdf", "z extras")
        return names.map { it to adapter.positionOf(File(dir, it).absolutePath) }
            .filter { it.second >= 0 }.sortedBy { it.second }.map { it.first }
    }

    @Test
    fun sortAppliesLiveWithMissingValuesLast() {
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        assertEquals(listOf("z extras", "a (2001).cbz", "b (2010).cbz", "c.pdf"), activity.shown())

        prefs.setSort(Prefs.TAB_BROWSE, TabSort(SortLevel(SortKey.YEAR)))
        idle()
        assertEquals(listOf("z extras", "b (2010).cbz", "a (2001).cbz", "c.pdf"), activity.shown())

        prefs.setSort(Prefs.TAB_BROWSE, TabSort(SortLevel(SortKey.YEAR, flipped = true), foldersFirst = false))
        idle()
        assertEquals(listOf("a (2001).cbz", "b (2010).cbz", "c.pdf", "z extras"), activity.shown())
    }

    @Test
    fun filterShowsAChipThatClearsIt() {
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        val chip = activity.findViewById<Chip>(R.id.filter_chip)
        assertEquals(View.GONE, chip.visibility)

        prefs.setFilter(Prefs.TAB_BROWSE, LibraryFilter(types = setOf(EntryType.PDF)))
        idle()
        assertEquals(listOf("c.pdf"), activity.shown())
        assertEquals(View.VISIBLE, chip.visibility)

        chip.performCloseIconClick()
        idle()
        assertEquals(LibraryFilter.NONE, prefs.filter(Prefs.TAB_BROWSE))
        assertEquals(View.GONE, chip.visibility)
        assertEquals(4, activity.shown().size)
    }

    @Test
    fun browsingKeepsFoldersReachable() {
        prefs.setFilter(Prefs.TAB_BROWSE, LibraryFilter(minLength = 1, direction = ReadingDirection.RTL))
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        assertEquals(listOf("z extras"), activity.shown())
    }

    @Test
    fun unreadableBooksAreNotRemembered() {
        BookFactsCache.reset()
        val junk = File(dir, "b (2010).cbz").apply { writeText("not an archive") }
        prefs.setSort(Prefs.TAB_BROWSE, TabSort(SortLevel(SortKey.LENGTH)))
        Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        assertNull(BookFactsCache(ApplicationProvider.getApplicationContext()).get(junk.absolutePath, junk.length()))
    }

    @Test
    fun bookmarksSortBySize() {
        File(dir, "a (2001).cbz").writeBytes(ByteArray(10))
        File(dir, "b (2010).cbz").writeBytes(ByteArray(30))
        File(dir, "c.pdf").writeBytes(ByteArray(20))
        val library = Library(ApplicationProvider.getApplicationContext())
        for ((i, name) in listOf("a (2001).cbz", "b (2010).cbz", "c.pdf").withIndex()) {
            library.addBookmark(Bookmark(File(dir, name).absolutePath, name, 0, "", 1000L + i))
        }
        prefs.startupTab = Prefs.TAB_BOOKMARKS
        prefs.setSort(Prefs.TAB_BOOKMARKS, TabSort(SortLevel(SortKey.SIZE)))
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        assertEquals(listOf("b (2010).cbz", "c.pdf", "a (2001).cbz"), activity.shown())
    }

    @Test
    fun filtersBelongToTheirTab() {
        prefs.setFilter(Prefs.TAB_HISTORY, LibraryFilter(types = setOf(EntryType.PDF)))
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        assertEquals(View.GONE, activity.findViewById<Chip>(R.id.filter_chip).visibility)
        assertEquals(4, activity.shown().size)
    }
}
