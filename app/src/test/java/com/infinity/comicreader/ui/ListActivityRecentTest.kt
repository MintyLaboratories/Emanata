package com.infinity.comicreader.ui

import com.infinity.comicreader.testing.bookId
import android.Manifest
import android.app.Application
import android.os.Environment
import android.view.View
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.testing.idleUntil
import com.infinity.comicreader.data.HistoryItem
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** The "Recent" row of Browse: always there with history, following the reader at once, redrawn only when it changes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ListActivityRecentTest {

    private lateinit var context: Application
    private lateinit var library: Library
    private lateinit var root: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        for (name in listOf("ui", "library")) context.getSharedPreferences(name, 0).edit().clear().commit()
        root = Environment.getExternalStorageDirectory()
        library = Library(context)
    }

    /** True once the screen waits on nothing but the test: listed, favorites classified, facts read, presence asked, its menu looked into. */
    private fun ListActivity.quiet() =
        listJob?.isCompleted == true && listOf(classifyJob, factsJob, presenceJob, moreLook).none { it?.isActive == true }

    /** Runs the main looper until the screen is quiet. */
    private fun ListActivity.settle() = idleUntil("the library screen quiet") { quiet() }

    private fun book(folder: String, name: String): File =
        File(root, folder).apply { mkdirs() }.let { File(it, name).apply { createNewFile() } }

    private fun read(book: File) = library.addHistory(HistoryItem(book.absolutePath, book.name, 1, 10, System.currentTimeMillis(), bookId(book)))

    private fun launch(): ListActivity {
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        activity.settle()
        return activity
    }

    private fun ListActivity.adapter() = findViewById<RecyclerView>(R.id.recycler).adapter as BookAdapter

    @Test
    fun anEmptyFolderKeepsTheRow() {
        read(book("Series", "01.cbz"))
        val empty = File(root, "Empty").apply { mkdirs() }
        Prefs(context).lastPath = empty.absolutePath
        val activity = launch()
        assertEquals("Series", activity.adapter().header?.subtitle)
        assertEquals(1, activity.adapter().itemCount)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.empty_view).visibility)
    }

    @Test
    fun followsABookOpenedMeanwhileWithoutRedrawingTheList() {
        read(book("Series", "01.cbz"))
        Prefs(context).lastPath = File(root, "Series").absolutePath
        val activity = launch()
        val adapter = activity.adapter()
        val events = ArrayList<String>()
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                events += "all"
            }

            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
                events += "changed $positionStart+$itemCount"
            }

            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                events += "changed $positionStart+$itemCount"
            }

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                events += "inserted $positionStart+$itemCount"
            }

            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                events += "removed $positionStart+$itemCount"
            }
        })

        // Another book of the same folder: the row looks the same, nothing is redrawn.
        read(book("Series", "02.cbz"))
        activity.settle()
        assertEquals(emptyList<String>(), events)

        // A book elsewhere: only the row changes, and it takes there.
        read(book("Other", "01.cbz"))
        activity.settle()
        assertEquals(listOf("changed 0+1"), events)
        assertEquals("Other", adapter.header?.subtitle)
        val recycler = activity.findViewById<RecyclerView>(R.id.recycler)
        recycler.measure(0, 0)
        recycler.layout(0, 0, 1000, 2000)
        val row = recycler.findViewHolderForAdapterPosition(0)
        assertNotNull(row)
        row!!.itemView.performClick()
        activity.settle()
        assertEquals(File(root, "Other").absolutePath, activity.findViewById<CrumbBar>(R.id.pathbar).crumbs.last().path)
    }
}
