package com.infinity.comicreader.ui

import com.infinity.comicreader.testing.bookId
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
import com.infinity.comicreader.testing.idleUntil
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
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.fakes.RoboMenuItem
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

    /** True once the screen waits on nothing but the test: listed, favorites classified, facts read, presence asked, its menu looked into. */
    private fun ListActivity.quiet() =
        listJob?.isCompleted == true && listOf(classifyJob, factsJob, presenceJob, moreLook).none { it?.isActive == true }

    /** Runs the main looper until the screen is quiet. */
    private fun ListActivity.settle() = idleUntil("the library screen quiet") { quiet() }

    /** Names shown, in order, read through the adapter's positions. */
    private fun ListActivity.shown(): List<String> {
        val adapter = findViewById<RecyclerView>(R.id.recycler).adapter as BookAdapter
        val names = listOf("a (2001).cbz", "b (2010).cbz", "c.pdf", "z extras")
        return names.map { it to adapter.positionOf(File(dir, it).absolutePath) }
            .filter { it.second >= 0 }.sortedBy { it.second }.map { it.first }
    }

    /** No work of this test's screens runs on into the next. */
    @After
    fun awaitLeftovers() = awaitLeftoverWork()

    @Test
    fun sortAppliesLiveWithMissingValuesLast() {
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        activity.settle()
        assertEquals(listOf("z extras", "a (2001).cbz", "b (2010).cbz", "c.pdf"), activity.shown())

        prefs.setSort(Prefs.TAB_BROWSE, TabSort(SortLevel(SortKey.YEAR)))
        activity.settle()
        assertEquals(listOf("z extras", "b (2010).cbz", "a (2001).cbz", "c.pdf"), activity.shown())

        prefs.setSort(Prefs.TAB_BROWSE, TabSort(SortLevel(SortKey.YEAR, flipped = true), foldersFirst = false))
        activity.settle()
        assertEquals(listOf("a (2001).cbz", "b (2010).cbz", "c.pdf", "z extras"), activity.shown())
    }

    @Test
    fun filterShowsAChipThatClearsIt() {
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        activity.settle()
        val chip = activity.findViewById<Chip>(R.id.filter_chip)
        assertEquals(View.GONE, chip.visibility)

        prefs.setFilter(Prefs.TAB_BROWSE, LibraryFilter(types = setOf(EntryType.PDF)))
        activity.settle()
        assertEquals(listOf("c.pdf"), activity.shown())
        assertEquals(View.VISIBLE, chip.visibility)

        chip.performCloseIconClick()
        activity.settle()
        assertEquals(LibraryFilter.NONE, prefs.filter(Prefs.TAB_BROWSE))
        assertEquals(View.GONE, chip.visibility)
        assertEquals(4, activity.shown().size)
    }

    @Test
    fun browsingKeepsFoldersReachable() {
        prefs.setFilter(Prefs.TAB_BROWSE, LibraryFilter(minLength = 1, direction = ReadingDirection.RTL))
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        activity.settle()
        assertEquals(listOf("z extras"), activity.shown())
    }

    @Test
    fun unreadableBooksAreNotRemembered() {
        BookFactsCache.reset()
        val junk = File(dir, "b (2010).cbz").apply { writeText("not an archive") }
        prefs.setSort(Prefs.TAB_BROWSE, TabSort(SortLevel(SortKey.LENGTH)))
        Robolectric.buildActivity(ListActivity::class.java).setup().get().settle()
        val cache = BookFactsCache(ApplicationProvider.getApplicationContext()).apply { load() }
        assertNull(cache.get(bookId(junk)))
    }

    @Test
    fun pageCountsWaitWhileTheLibraryIsOutOfSight() {
        BookFactsCache.reset()
        val page = com.infinity.comicreader.reader.TestImages.png(4, 6)
        val books = (1..5).map { com.infinity.comicreader.reader.Fixtures.zip(File(dir, "d$it.cbz"), "01.png" to page, "02.png" to page) }
        prefs.setSort(Prefs.TAB_BROWSE, TabSort(SortLevel(SortKey.LENGTH)))
        // Created but not yet in sight, as under a book opened over it.
        val controller = Robolectric.buildActivity(ListActivity::class.java).create()
        // Facts are read only once the library is started: nothing can be read before.
        shadowOf(Looper.getMainLooper()).idle()
        fun counted(): Int {
            val cache = BookFactsCache(ApplicationProvider.getApplicationContext()).apply { load() }
            return books.count { cache.get(bookId(it))?.pages == 2 }
        }
        assertTrue("read ${counted()} while out of sight", counted() < books.size)
        controller.start().resume()
        controller.get().settle()
        assertEquals(books.size, counted())
    }

    @Test
    fun bookmarksSortBySize() {
        File(dir, "a (2001).cbz").writeBytes(ByteArray(10))
        File(dir, "b (2010).cbz").writeBytes(ByteArray(30))
        File(dir, "c.pdf").writeBytes(ByteArray(20))
        val library = Library(ApplicationProvider.getApplicationContext())
        for ((i, name) in listOf("a (2001).cbz", "b (2010).cbz", "c.pdf").withIndex()) {
            library.addBookmark(Bookmark(File(dir, name).absolutePath, name, 0, "", 1000L + i, bookId(File(dir, name))))
        }
        prefs.startupTab = Prefs.TAB_BOOKMARKS
        prefs.setSort(Prefs.TAB_BOOKMARKS, TabSort(SortLevel(SortKey.SIZE)))
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        activity.settle()
        assertEquals(listOf("b (2010).cbz", "c.pdf", "a (2001).cbz"), activity.shown())
    }

    @Test
    fun sortAndFilterPopupsSurviveRotation() {
        val controller = Robolectric.buildActivity(ListActivity::class.java).setup()
        controller.get().settle()
        for ((item, kind) in listOf(R.id.menu_sort to TabDialogFragment.Kind.SORT, R.id.menu_filter to TabDialogFragment.Kind.FILTER)) {
            controller.get().onOptionsItemSelected(RoboMenuItem(item))
            controller.get().settle()
            controller.recreate()
            controller.get().settle()
            val fragment = controller.get().supportFragmentManager.fragments.filterIsInstance<TabDialogFragment>().single()
            assertEquals(kind.name, fragment.requireArguments().getString("kind"))
            assertTrue(fragment.dialog!!.isShowing)
            fragment.dismiss()
            controller.get().settle()
        }
    }

    @Test
    fun filtersBelongToTheirTab() {
        prefs.setFilter(Prefs.TAB_HISTORY, LibraryFilter(types = setOf(EntryType.PDF)))
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        activity.settle()
        assertEquals(View.GONE, activity.findViewById<Chip>(R.id.filter_chip).visibility)
        assertEquals(4, activity.shown().size)
    }
}
