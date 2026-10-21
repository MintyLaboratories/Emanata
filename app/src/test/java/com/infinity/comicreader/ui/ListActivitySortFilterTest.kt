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
import com.infinity.comicreader.data.EntryType
import com.infinity.comicreader.data.LibraryFilter
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.SortKey
import com.infinity.comicreader.data.SortLevel
import com.infinity.comicreader.data.TabSort
import org.junit.Assert.assertEquals
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
    fun filtersBelongToTheirTab() {
        prefs.setFilter(Prefs.TAB_HISTORY, LibraryFilter(types = setOf(EntryType.PDF)))
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        assertEquals(View.GONE, activity.findViewById<Chip>(R.id.filter_chip).visibility)
        assertEquals(4, activity.shown().size)
    }
}
