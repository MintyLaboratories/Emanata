package com.infinity.comicreader.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.data.BookEntry
import com.infinity.comicreader.data.CoverStyle
import com.infinity.comicreader.data.EntryType
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.Thumbs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Switching away drops covers still loading, and covers made before show at once whatever is being made. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BookAdapterCoversTest {

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: BookAdapter
    private val release = CountDownLatch(1)

    /** Books whose covers are made slowly (named "slow…", until [release]), one whose cover was made before, and others made at once. */
    private inner class Fake : CoverSource {
        val started: MutableList<String> = Collections.synchronizedList(ArrayList())
        val stops: MutableList<() -> Boolean> = Collections.synchronizedList(ArrayList())

        override fun inMemory(context: Context, entry: BookEntry, width: Int, height: Int, style: CoverStyle): Bitmap? = null

        override fun cached(context: Context, entry: BookEntry, width: Int, height: Int, style: CoverStyle): Bitmap? =
            if (entry.name == "made.cbz") Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) else null

        override fun load(
            context: Context,
            entry: BookEntry,
            charset: String,
            width: Int,
            height: Int,
            style: CoverStyle,
            canceled: () -> Boolean
        ): Bitmap? {
            started += entry.name
            stops += canceled
            if (entry.name.startsWith("slow")) release.await(10, TimeUnit.SECONDS)
            return if (canceled()) null else Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
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

    /** Layered covers: a red quick cover first, then the blue full one, each step logged. */
    private class Layered : CoverSource {
        val steps: MutableList<String> = Collections.synchronizedList(ArrayList())

        private fun colored(width: Int, height: Int, color: Int) =
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

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
        ): Bitmap {
            steps += "full ${entry.name}"
            return colored(width, height, Color.BLUE)
        }

        override fun loadFirst(
            context: Context,
            entry: BookEntry,
            charset: String,
            width: Int,
            height: Int,
            style: CoverStyle,
            canceled: () -> Boolean
        ): Thumbs.Cover {
            steps += "first ${entry.name}"
            Thread.sleep(30)
            return Thumbs.Cover(colored(width, height, Color.RED), full = false)
        }
    }

    private val fake = Fake()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        recycler = RecyclerView(activity).apply { layoutManager = LinearLayoutManager(activity) }
        activity.setContentView(FrameLayout(activity).apply { addView(recycler) })
        adapter = BookAdapter(grid = false, prefs = Prefs(context), onClick = {}, onLongClick = {}, onStarClick = {})
        adapter.covers = fake
        recycler.adapter = adapter
    }

    @After
    fun tearDown() {
        release.countDown()
        adapter.release()
    }

    private fun book(name: String) = BookEntry(File("/books/$name"), name, false, EntryType.ARCHIVE, 100, 1L)

    private fun layout() {
        recycler.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.EXACTLY)
        )
        recycler.layout(0, 0, 1000, 4000)
    }

    /** Runs the main looper until [done], for up to two seconds. */
    private fun until(done: () -> Boolean) {
        val end = System.currentTimeMillis() + 2000
        while (!done() && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun drawn(position: Int) = (recycler.findViewHolderForAdapterPosition(position) as BookAdapter.BookHolder?)?.thumb?.drawable

    @Test
    fun layeredCoversShowTheirFirstLayersFirstThenTheFullCovers() {
        val layered = Layered()
        adapter.covers = layered
        adapter.submit((1..6).map { book("$it.cbz") })
        layout()
        until { layered.steps.size == 12 }
        assertEquals((1..6).map { "first $it.cbz" }.toSet(), layered.steps.take(6).toSet())
        assertEquals((1..6).map { "full $it.cbz" }.toSet(), layered.steps.drop(6).toSet())
        val shown = 0 until recycler.childCount
        until { shown.all { (drawn(it) as? BitmapDrawable)?.bitmap?.getPixel(0, 0) == Color.BLUE } }
        for (i in shown) assertEquals(Color.BLUE, (drawn(i) as BitmapDrawable).bitmap.getPixel(0, 0))
    }

    @Test
    fun rowsLeftBehindAskForNoCoverUntilTheNextList() {
        val layered = Layered()
        adapter.covers = layered
        val books = (1..2).map { book("$it.cbz") }
        adapter.submit(books)
        layout()
        until { layered.steps.size == 4 }
        adapter.cancelCovers()
        // The next tab's view redraws the old rows before its own list arrives.
        adapter.options = RowOptions(density = com.infinity.comicreader.data.ListDensity.LARGE)
        layout()
        until { false }
        assertEquals(4, layered.steps.size)
        adapter.submit(listOf(book("3.cbz")))
        layout()
        until { layered.steps.size == 6 }
        assertEquals(listOf("first 3.cbz", "full 3.cbz"), layered.steps.drop(4))
    }

    @Test
    fun aCoverMadeBeforeShowsAtOnceAfterSwitchingAway() {
        adapter.submit((1..8).map { book("slow $it.cbz") })
        layout()
        until { fake.started.size == 3 }
        assertEquals("three covers are made at a time", 3, fake.started.size)

        adapter.cancelCovers()
        adapter.submit(listOf(book("made.cbz")))
        layout()
        until { drawn(0) != null }

        assertNotNull("shown while the slow covers are still being made", drawn(0))
        assertTrue("the slow covers were told to stop", fake.stops.all { it() })
        release.countDown()
        until { false }
        assertEquals("the covers waiting their turn never started " + fake.started, 3, fake.started.size)
    }

    @Test
    fun coversStuckAfterSwitchingAwayLeaveRoomForTheNewView() {
        adapter.submit((1..8).map { book("slow $it.cbz") })
        layout()
        until { fake.started.size == 3 }

        adapter.cancelCovers()
        adapter.submit(listOf(book("new.cbz")))
        layout()
        until { drawn(0) != null }

        assertNotNull("made while the slow covers are still stuck", drawn(0))
        assertEquals(fake.started.toString(), 4, fake.started.size)
    }
}
