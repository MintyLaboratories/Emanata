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
import com.infinity.comicreader.net.FakeRemoteFs
import com.infinity.comicreader.net.Metered
import com.infinity.comicreader.net.NetPolicy
import com.infinity.comicreader.net.NetRules
import com.infinity.comicreader.net.NetSkip
import com.infinity.comicreader.net.Remotes
import com.infinity.comicreader.net.ShareSpeed
import com.infinity.comicreader.testing.idleUntil
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

/** Switching away drops covers still loading, and covers made before show at once whatever is being made. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BookAdapterCoversTest {

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: BookAdapter
    private val release = CountDownLatch(1)

    /** Holds the look for covers made before of books named "late…" until counted down. */
    private val lookedUp = CountDownLatch(1)

    /** Books whose covers are made slowly (named "slow…", until [release]), one whose cover was made before, and others made at once. */
    private inner class Fake : CoverSource {
        /** Books whose covers made before were looked for: whether one is made next was decided as it was asked for. */
        val looked: MutableList<String> = Collections.synchronizedList(ArrayList())
        val started: MutableList<String> = Collections.synchronizedList(ArrayList())
        val stops: MutableList<() -> Boolean> = Collections.synchronizedList(ArrayList())

        /** Books whose covers were made, or given up. */
        val ended: MutableList<String> = Collections.synchronizedList(ArrayList())

        override fun inMemory(context: Context, entry: BookEntry, width: Int, height: Int, style: CoverStyle): Bitmap? = null

        override fun cached(context: Context, entry: BookEntry, width: Int, height: Int, style: CoverStyle): Bitmap? {
            looked += entry.name
            if (entry.name.startsWith("late")) lookedUp.await()
            return if (entry.name == "made.cbz") Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) else null
        }

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
            if (entry.name.startsWith("slow")) release.await()
            return (if (canceled()) null else Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)).also { ended += entry.name }
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

    /** Layered covers: a red quick cover first, then the blue full one, each step logged; [hold] runs before a first layer is given. */
    private class Layered(private val hold: (BookEntry) -> Unit = {}) : CoverSource {
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
            hold(entry)
            return Thumbs.Cover(colored(width, height, Color.RED), full = false)
        }
    }

    private val fake = Fake()

    /** Holds the first layer of "1.cbz" until counted down. */
    private val firstHeld = CountDownLatch(1)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        recycler = RecyclerView(activity).apply { layoutManager = LinearLayoutManager(activity) }
        // The window's own layout keeps the list at [layout]'s size: a row it cut off would give its cover's place away.
        activity.setContentView(FrameLayout(activity).apply { addView(recycler, FrameLayout.LayoutParams(WIDTH, HEIGHT)) })
        adapter = BookAdapter(grid = false, prefs = Prefs(context), onClick = {}, onLongClick = {}, onStarClick = {})
        adapter.covers = fake
        recycler.adapter = adapter
    }

    @After
    fun tearDown() {
        release.countDown()
        lookedUp.countDown()
        firstHeld.countDown()
        adapter.release()
    }

    private fun book(name: String) = BookEntry(File("/books/$name"), name, false, EntryType.ARCHIVE, 100, 1L)

    private fun layout() {
        recycler.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY)
        )
        recycler.layout(0, 0, WIDTH, HEIGHT)
    }

    private fun drawn(position: Int) = (recycler.findViewHolderForAdapterPosition(position) as BookAdapter.BookHolder?)?.thumb?.drawable

    private fun shareBook(name: String) = BookEntry(Remotes.file("/net/s1/$name", askNetwork = false), name, false, EntryType.ARCHIVE, 100, 1L)

    @Test
    fun onAMeteredConnectionShareCoversAreNotMadeButThoseMadeBeforeShow() {
        Remotes.serve(listOf(FakeRemoteFs.share())) { throw AssertionError("no reads") }
        Metered.probe = { Metered.Link(connected = true, notMetered = false, activeMetered = true, cellular = true) }
        Metered.forget()
        try {
            adapter.submit(listOf(shareBook("a.cbz"), shareBook("made.cbz"), book("device.cbz")))
            layout()
            // A share book's cover is made or not as decided when asked for, before the look for one made before.
            idleUntil("device.cbz's cover started, made.cbz's drawn, a.cbz's looked for") {
                fake.started.isNotEmpty() && drawn(1) != null && "a.cbz" in fake.looked
            }
            // A held cover would be made only after its look ends and the load goes on on the main thread.
            idleUntil("covers settled") { !adapter.coversBusy }
            assertEquals(listOf("device.cbz"), fake.started.toList())
            assertNotNull(drawn(1))
            // With covers not skipped on metered connections they are made.
            NetPolicy.rules = NetRules(metered = emptySet())
            adapter.submit(listOf(shareBook("b.cbz")))
            layout()
            idleUntil("b.cbz's cover started") { "b.cbz" in fake.started }
            assertTrue("b.cbz" in fake.started)
        } finally {
            NetPolicy.rules = NetRules()
            Metered.probe = { Metered.Link(connected = true, notMetered = true, activeMetered = false) }
            Metered.forget()
            Remotes.serve(emptyList()) { throw AssertionError("no shares") }
        }
    }

    @Test
    fun coversHeldOnAMeteredConnectionAreMadeOnceItIsNot() {
        Remotes.serve(listOf(FakeRemoteFs.share())) { throw AssertionError("no reads") }
        Metered.probe = { Metered.Link(connected = true, notMetered = false, activeMetered = true, cellular = true) }
        Metered.forget()
        try {
            adapter.submit(listOf(shareBook("a.cbz"), shareBook("b.cbz")))
            layout()
            idleUntil("both covers looked for") { fake.looked.containsAll(listOf("a.cbz", "b.cbz")) }
            idleUntil("covers settled") { !adapter.coversBusy }
            assertTrue(fake.started.isEmpty())
            Metered.probe = { Metered.Link(connected = true, notMetered = true, activeMetered = false) }
            Metered.forget()
            adapter.retryHeld()
            idleUntil("both covers started") { fake.started.size == 2 }
            assertEquals(setOf("a.cbz", "b.cbz"), fake.started.toSet())
        } finally {
            Metered.probe = { Metered.Link(connected = true, notMetered = true, activeMetered = false) }
            Metered.forget()
            Remotes.serve(emptyList()) { throw AssertionError("no shares") }
        }
    }

    @Test
    fun onASlowShareCoversOnScreenAreMade() {
        Remotes.serve(listOf(FakeRemoteFs.share().copy(speed = ShareSpeed.SLOW))) { throw AssertionError("no reads") }
        try {
            adapter.submit(listOf(shareBook("a.cbz"), shareBook("b.cbz")))
            layout()
            idleUntil("both covers started") { fake.started.size == 2 }
            assertEquals(setOf("a.cbz", "b.cbz"), fake.started.toSet())
            // Skipping any network use not asked for holds back those on screen too.
            NetPolicy.rules = NetRules(slow = setOf(NetSkip.ANY))
            adapter.submit(listOf(shareBook("c.cbz")))
            layout()
            idleUntil("c.cbz's cover looked for") { "c.cbz" in fake.looked }
            idleUntil("covers settled") { !adapter.coversBusy }
            assertTrue("c.cbz" !in fake.started)
        } finally {
            NetPolicy.rules = NetRules()
            Remotes.serve(emptyList()) { throw AssertionError("no shares") }
        }
    }

    @Test
    fun aRowBoundOffScreenOnASlowShareGetsItsCoverOnceAttachedEvenBeforeTheLookFinishes() {
        Remotes.serve(listOf(FakeRemoteFs.share().copy(speed = ShareSpeed.SLOW))) { throw AssertionError("no reads") }
        try {
            adapter.submit((0 until 400).map { shareBook("late$it.cbz") })
            layout()
            // Bound ahead of a scroll, as prefetch does: not attached, laid out at a row's size.
            val position = 399
            val row = recycler.getChildAt(0)
            assertEquals(null, recycler.findViewHolderForAdapterPosition(position))
            val pool = RecyclerView::class.java.getDeclaredField("mRecycler").apply { isAccessible = true }.get(recycler) as RecyclerView.Recycler
            val prefetched = pool.getViewForPosition(position)
            prefetched.measure(
                View.MeasureSpec.makeMeasureSpec(row.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(row.height, View.MeasureSpec.EXACTLY)
            )
            prefetched.layout(0, 0, row.width, row.height)
            val holder = recycler.getChildViewHolder(prefetched) as BookAdapter.BookHolder
            assertTrue(!prefetched.isAttachedToWindow)
            repeat(5) { shadowOf(Looper.getMainLooper()).idle() }
            // Attached while the look for a cover made before still runs.
            (recycler.parent as FrameLayout).addView(prefetched)
            adapter.onViewAttachedToWindow(holder)
            lookedUp.countDown()
            idleUntil("late$position.cbz's cover started") { "late$position.cbz" in fake.started }
            assertTrue("late$position.cbz" in fake.started)
        } finally {
            Remotes.serve(emptyList()) { throw AssertionError("no shares") }
        }
    }

    @Test
    fun batterySaverKeepsFirstLayersUntilItEnds() {
        val layered = Layered()
        adapter.covers = layered
        adapter.firstLayerOnly = true
        adapter.submit((1..3).map { book("$it.cbz") })
        layout()
        idleUntil("three first layers") { layered.steps.size == 3 }
        val shown = 0 until recycler.childCount
        // A row shows its first layer, then would ask for its full cover at once.
        idleUntil("every row red") { shown.all { (drawn(it) as? BitmapDrawable)?.bitmap?.getPixel(0, 0) == Color.RED } }
        // No full cover was asked for.
        idleUntil("first layers settled") { !adapter.coversBusy }
        assertEquals((1..3).map { "first $it.cbz" }.toSet(), layered.steps.toSet())
        adapter.firstLayerOnly = false
        layout()
        idleUntil("every row blue") { shown.all { (drawn(it) as? BitmapDrawable)?.bitmap?.getPixel(0, 0) == Color.BLUE } }
        assertEquals((1..3).map { "full $it.cbz" }.toSet(), layered.steps.filter { it.startsWith("full") }.toSet())
    }

    @Test
    fun layeredCoversShowTheirFirstLayersFirstThenTheFullCovers() {
        // The other first layers are given while one is still being made.
        val layered = Layered { if (it.name == "1.cbz") firstHeld.await() }
        adapter.covers = layered
        adapter.submit((1..6).map { book("$it.cbz") })
        layout()
        idleUntil("six first layers asked for") { layered.steps.size == 6 }
        firstHeld.countDown()
        idleUntil("twelve covers made") { layered.steps.size == 12 }
        assertEquals((1..6).map { "first $it.cbz" }.toSet(), layered.steps.take(6).toSet())
        assertEquals((1..6).map { "full $it.cbz" }.toSet(), layered.steps.drop(6).toSet())
        val shown = 0 until recycler.childCount
        idleUntil("every row blue") { shown.all { (drawn(it) as? BitmapDrawable)?.bitmap?.getPixel(0, 0) == Color.BLUE } }
        for (i in shown) assertEquals(Color.BLUE, (drawn(i) as BitmapDrawable).bitmap.getPixel(0, 0))
    }

    @Test
    fun rowsLeftBehindAskForNoCoverUntilTheNextList() {
        val layered = Layered()
        adapter.covers = layered
        val books = (1..2).map { book("$it.cbz") }
        adapter.submit(books)
        layout()
        idleUntil("both rows' covers made") { layered.steps.size == 4 }
        adapter.cancelCovers()
        // The next tab's view redraws the old rows before its own list arrives; they ask on the main thread.
        adapter.options = RowOptions(density = com.infinity.comicreader.data.ListDensity.LARGE)
        layout()
        idleUntil("old rows settled") { !adapter.coversBusy }
        assertEquals(4, layered.steps.size)
        adapter.submit(listOf(book("3.cbz")))
        layout()
        idleUntil("3.cbz's covers made") { layered.steps.size == 6 }
        assertEquals(listOf("first 3.cbz", "full 3.cbz"), layered.steps.drop(4))
    }

    @Test
    fun aCoverMadeBeforeShowsAtOnceAfterSwitchingAway() {
        adapter.submit((1..8).map { book("slow $it.cbz") })
        layout()
        idleUntil("three covers started") { fake.started.size == 3 }
        assertEquals("three covers are made at a time", 3, fake.started.size)

        adapter.cancelCovers()
        adapter.submit(listOf(book("made.cbz")))
        layout()
        idleUntil("made.cbz drawn") { drawn(0) != null }

        assertNotNull("shown while the slow covers are still being made", drawn(0))
        assertTrue("the slow covers were told to stop", fake.stops.all { it() })
        release.countDown()
        // The slow covers end and give their places back; those waiting their turn ended as they were dropped.
        idleUntil("the slow covers ended") { fake.ended.size == 3 && !adapter.coversBusy }
        assertEquals("the covers waiting their turn never started " + fake.started, 3, fake.started.size)
    }

    @Test
    fun coversStuckAfterSwitchingAwayLeaveRoomForTheNewView() {
        adapter.submit((1..8).map { book("slow $it.cbz") })
        layout()
        idleUntil("three covers started") { fake.started.size == 3 }

        adapter.cancelCovers()
        adapter.submit(listOf(book("new.cbz")))
        layout()
        idleUntil("new.cbz drawn") { drawn(0) != null }

        assertNotNull("made while the slow covers are still stuck", drawn(0))
        assertEquals(fake.started.toString(), 4, fake.started.size)
    }

    private companion object {
        /** The list's size, larger than the window's, so all eight rows show. */
        const val WIDTH = 1000
        const val HEIGHT = 4000
    }
}
