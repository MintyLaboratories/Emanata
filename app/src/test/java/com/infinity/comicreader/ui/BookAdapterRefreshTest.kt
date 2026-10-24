package com.infinity.comicreader.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.data.BookEntry
import com.infinity.comicreader.data.EntryType
import com.infinity.comicreader.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** Facts arriving after a listing redraw only the text of their rows: covers are never reloaded or blanked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BookAdapterRefreshTest {

    private lateinit var prefs: Prefs
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: BookAdapter
    private val events = ArrayList<String>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        prefs = Prefs(context)
        prefs.thumbnails = false
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        activity.setTheme(R.style.Theme_ComicReader)
        recycler = RecyclerView(activity).apply { layoutManager = LinearLayoutManager(activity) }
        activity.setContentView(FrameLayout(activity).apply { addView(recycler) })
        adapter = BookAdapter(grid = false, prefs = prefs, onClick = {}, onLongClick = {}, onStarClick = {})
        recycler.adapter = adapter
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                events += "all"
            }

            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                events += "changed $positionStart ${payload ?: "whole"}"
            }
        })
    }

    private fun book(name: String, pages: Int = -1, size: Long = 100) =
        BookEntry(File("/books/$name"), name, false, EntryType.ARCHIVE, size, 1L, pages = pages)

    private fun layout() {
        shadowOf(Looper.getMainLooper()).idle()
        recycler.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY)
        )
        recycler.layout(0, 0, 1000, 2000)
    }

    private fun holder(position: Int) = recycler.findViewHolderForAdapterPosition(position) as BookAdapter.BookHolder

    @Test
    fun pageCountsRedrawOnlyTheTextOfTheirRows() {
        adapter.submit(listOf(book("a.cbz"), book("b.cbz"), book("c.cbz")))
        layout()
        val cover = BitmapDrawable(recycler.resources, Bitmap.createBitmap(4, 6, Bitmap.Config.ARGB_8888))
        holder(1).thumb.setImageDrawable(cover)
        val view = holder(1).itemView
        events.clear()

        adapter.submit(listOf(book("a.cbz"), book("b.cbz", pages = 24), book("c.cbz")))
        layout()

        assertEquals(listOf("changed 1 details"), events)
        assertSame(view, holder(1).itemView)
        assertSame(cover, holder(1).thumb.drawable)
        assertTrue(holder(1).subtitle.text.toString().contains("24"))
    }

    @Test
    fun aChangedFileRedrawsItsWholeRow() {
        adapter.submit(listOf(book("a.cbz"), book("b.cbz")))
        layout()
        events.clear()
        adapter.submit(listOf(book("a.cbz"), book("b.cbz", size = 200)))
        assertEquals(listOf("changed 1 whole"), events)
    }

    @Test
    fun otherRowsRedrawTheList() {
        adapter.submit(listOf(book("a.cbz"), book("b.cbz")))
        layout()
        events.clear()
        adapter.submit(listOf(book("b.cbz"), book("a.cbz")))
        assertEquals(listOf("all"), events)
    }

    @Test
    fun rowsKeepTheirIdsAndBookmarksOfOneBookDiffer() {
        val a = book("a.cbz")
        assertEquals(RowChanges.idOf(a), RowChanges.idOf(a.copy(pages = 3)))
        assertNotEquals(RowChanges.idOf(a.copy(bookmarkPage = 1)), RowChanges.idOf(a.copy(bookmarkPage = 2)))
        assertTrue(RowChanges.idOf(a) >= 0)
        assertTrue(RowChanges.sameRows(listOf(a), listOf(a.copy(read = true))))
        assertTrue(!RowChanges.sameRows(listOf(a.copy(bookmarkPage = 1)), listOf(a.copy(bookmarkPage = 2))))
    }
}
