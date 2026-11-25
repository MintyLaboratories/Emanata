package minty.emanata.ui

import android.app.Application
import android.content.Context
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
import minty.emanata.R
import minty.emanata.data.BookEntry
import minty.emanata.data.BookId
import minty.emanata.data.CoverStyle
import minty.emanata.data.EntryType
import minty.emanata.data.Library
import minty.emanata.data.Prefs
import minty.emanata.data.ReadingDirection
import minty.emanata.data.Thumbs
import minty.emanata.testing.idleUntil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
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
import java.util.concurrent.CountDownLatch

/** Facts arriving after a listing redraw only the text of their rows: covers are never reloaded or blanked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BookAdapterRefreshTest {

    private lateinit var prefs: Prefs
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: BookAdapter
    private val events = ArrayList<String>()

    /** The cover sources of a test, opened after it so no load stays held. */
    private val gates = ArrayList<Gated>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        prefs = Prefs(context)
        prefs.thumbnails = false
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        activity.setTheme(R.style.Theme_Emanata)
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

    @After
    fun tearDown() {
        gates.forEach { it.release.countDown() }
        adapter.release()
    }

    private fun gated() = Gated().also { gates += it }

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
        assertEquals(listOf("changed 1 cover"), events)
    }

    @Test
    fun aCoverPageGivenSinceTheLastListRedrawsItsCover() {
        val id = BookId(0x1234)
        val b = book("b.cbz").copy(id = id)
        adapter.submit(listOf(book("a.cbz"), b))
        layout()
        events.clear()
        // Set as cover in the reader: the entry is the same, the library's covers are not.
        adapter.hiddenPages = { Library.Hidden(emptyMap(), emptyMap(), emptyMap(), mapOf(id to 2)) }
        adapter.submit(listOf(book("a.cbz"), b))
        assertEquals(listOf("changed 1 cover"), events)
        // Given back its first page while read progress arrives: still the cover.
        adapter.hiddenPages = { Library.Hidden(emptyMap(), emptyMap(), emptyMap()) }
        events.clear()
        adapter.submit(listOf(book("a.cbz"), b.copy(pages = 4)))
        assertEquals(listOf("changed 1 cover"), events)
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

    /** Covers made once [release] opens. */
    private class Gated : CoverSource {
        val release = CountDownLatch(1)

        override fun inMemory(context: Context, entry: BookEntry, width: Int, height: Int, style: CoverStyle): Bitmap? = null

        override fun cached(context: Context, entry: BookEntry, width: Int, height: Int, style: CoverStyle): Bitmap? = null

        override fun load(
            context: Context,
            entry: BookEntry,
            charset: String,
            width: Int,
            height: Int,
            style: CoverStyle,
            canceled: () -> Boolean
        ): Bitmap? {
            release.await()
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }

        override fun loadFirst(
            context: Context,
            entry: BookEntry,
            charset: String,
            width: Int,
            height: Int,
            style: CoverStyle,
            canceled: () -> Boolean
        ): Thumbs.Cover? = load(context, entry, charset, width, height, style, canceled)?.let { Thumbs.Cover(it, full = true) }
    }

    @Test
    fun aDirectionFoundRedrawsOnlyTheText() {
        adapter.covers = gated()
        adapter.submit(listOf(book("a.cbz"), book("b.cbz")))
        layout()
        val cover = BitmapDrawable(recycler.resources, Bitmap.createBitmap(4, 6, Bitmap.Config.ARGB_8888))
        holder(1).thumb.setImageDrawable(cover)
        events.clear()

        adapter.submit(listOf(book("a.cbz"), book("b.cbz").copy(direction = ReadingDirection.RTL)))
        layout()

        assertEquals(listOf("changed 1 details"), events)
        assertSame(cover, holder(1).thumb.drawable)
    }

    @Test
    fun aMovedCoverKeepsTheOldOneUntilTheNewIsDrawn() {
        prefs.thumbnails = true
        val id = BookId(0x1234)
        val a = book("a.cbz").copy(id = id)
        val first = gated()
        adapter.covers = first
        adapter.submit(listOf(a))
        layout()
        first.release.countDown()
        idleUntil("the first cover drawn") { holder(0).thumb.drawable != null }
        val shown = holder(0).thumb.drawable
        events.clear()

        val waiting = gated()
        adapter.covers = waiting
        adapter.hiddenPages = { Library.Hidden(emptyMap(), emptyMap(), emptyMap(), mapOf(id to 2)) }
        adapter.submit(listOf(a))
        layout()
        assertEquals(listOf("changed 0 cover"), events)
        assertSame("the old cover stays meanwhile", shown, holder(0).thumb.drawable)
        assertEquals(View.GONE, holder(0).placeholder.visibility)

        waiting.release.countDown()
        idleUntil("the new cover drawn") { holder(0).thumb.drawable !== shown }
        assertNotSame(shown, holder(0).thumb.drawable)
    }
}
