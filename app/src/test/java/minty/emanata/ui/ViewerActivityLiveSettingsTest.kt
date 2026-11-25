package minty.emanata.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager2.widget.ViewPager2
import minty.emanata.R
import minty.emanata.ai.OfflineDownloads
import minty.emanata.data.Library
import minty.emanata.data.Prefs
import minty.emanata.data.ResumeMode
import minty.emanata.reader.ArchiveSource
import minty.emanata.reader.Fixtures
import minty.emanata.reader.NameCharset
import minty.emanata.reader.ZipReader
import minty.emanata.testing.idleUntil
import minty.emanata.testing.muteSettingsFeed
import minty.emanata.testing.ref
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.time.Duration
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Settings changed while the reader is open apply to it without recreating it: changed
 * while it shows (a sheet or dialog over it), or in Settings on top of it and seen on return.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerActivityLiveSettingsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Turning AI on asks for its default model: kept off the network. */
    @get:Rule
    val downloads = OfflineDownloads()

    private lateinit var context: Application
    private lateinit var prefs: Prefs
    private var controller: ActivityController<ViewerActivity>? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = Prefs(context)
        prefs.resumeMode = ResumeMode.NO
    }

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        NameCharset.overrides = emptyMap()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** A book of three pages; [real] ones decode (with native graphics), others are a byte each. */
    private fun book(real: Boolean = false): File {
        val pages = (0 until 3).map { i ->
            val bytes = if (!real) byteArrayOf(0) else {
                val bitmap = Bitmap.createBitmap(IntArray(300 * 450) { (0xFF shl 24) or (i * 40 shl 8) or (it % 300) }, 300, 450, Bitmap.Config.ARGB_8888)
                ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            }
            Fixtures.entry("p%02d.png".format(i), bytes)
        }
        return Fixtures.zip(File(tmp.newFolder("shelf"), "Book A.cbz"), *pages.toTypedArray())
    }

    /** The reader on [book], at shown page [page] when given; recreated from [state] when given. */
    private fun launch(book: File = book(), page: Int? = null, state: Bundle? = null): ActivityController<ViewerActivity> {
        val intent = Intent(context, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_PATH, book.absolutePath)
        page?.let { intent.putExtra(ViewerActivity.EXTRA_PAGE, it) }
        val built = Robolectric.buildActivity(ViewerActivity::class.java, intent)
        val c = if (state == null) built.setup() else built.setup(state)
        controller = c
        val viewer = c.get()
        val progress = viewer.findViewById<View>(R.id.viewer_progress)
        idleUntil("the book open and all its open started done") { progress.visibility != View.VISIBLE && viewer.openSettled() }
        return c
    }

    /** Settings on top of the reader: [change] is made while it is stopped, then it shows again. */
    private fun ActivityController<ViewerActivity>.away(change: () -> Unit) {
        pause().stop()
        change()
        start().resume()
        idle()
    }

    private fun descendants(v: View): List<View> =
        listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()

    /** The page images in the paged list. */
    private fun ViewerActivity.pages(): List<ZoomImageView> =
        descendants(findViewById<ViewPager2>(R.id.viewer_pager)).filterIsInstance<ZoomImageView>().also { assertTrue("no page shown", it.isNotEmpty()) }

    // ---------------------------------------------------------------- reader gestures

    @Test
    fun doubleTapTurnedOffWhileReadingAppliesToThePagesShown() {
        val viewer = launch().get()
        val adapter = viewer.pageAdapter
        val shown = viewer.pages()
        assertTrue(shown.all { it.doubleTapEnabled })
        prefs.doubleTapZoom = false
        idle()
        // The same pages, changed in place.
        assertSame(adapter, viewer.pageAdapter)
        assertFalse(shown.any { it.doubleTapEnabled })
        assertFalse(viewer.pages().any { it.doubleTapEnabled })
    }

    @Test
    fun doubleTapChangedInSettingsAppliesOnReturnEachWay() {
        val c = launch()
        val adapter = c.get().pageAdapter
        c.away { prefs.doubleTapZoom = false }
        assertSame(adapter, c.get().pageAdapter)
        assertFalse(c.get().pages().any { it.doubleTapEnabled })
        c.away { prefs.doubleTapZoom = true }
        assertSame(adapter, c.get().pageAdapter)
        assertTrue(c.get().pages().all { it.doubleTapEnabled })
    }

    /** The page image of the page shown. */
    private fun ViewerActivity.shownPage(): ZoomImageView {
        val pager = findViewById<ViewPager2>(R.id.viewer_pager)
        val holder = (pager.getChildAt(0) as RecyclerView).findViewHolderForAdapterPosition(pager.currentItem)
        assertNotNull("the page shown has a view", holder)
        return descendants(holder!!.itemView).filterIsInstance<ZoomImageView>().first()
    }

    /** Two quick taps in the middle of [page], then past the wait for a third: all the pair does is done. */
    private fun doubleTap(page: ZoomImageView) {
        val x = page.width / 2f
        val y = page.height / 2f
        fun tap() {
            val t = SystemClock.uptimeMillis()
            page.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
            page.dispatchTouchEvent(MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0))
        }
        tap()
        // The second down 90 ms after the first lift: a double tap (40 ms at least).
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        tap()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getDoubleTapTimeout() + 100L))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun doubleTapTurnedOffInSettingsNoLongerZoomsAndOnAgainDoes() {
        val c = launch(book(real = true))
        val adapter = c.get().pageAdapter
        idleUntil("the page shown decoded") { c.get().shownPage().let { it.drawable != null && it.width > 0 } }
        c.away { prefs.doubleTapZoom = false }
        val page = c.get().shownPage()
        doubleTap(page)
        assertFalse("double-tap zoomed while turned off", page.isZoomed)
        c.away { prefs.doubleTapZoom = true }
        assertSame(page, c.get().shownPage())
        doubleTap(page)
        assertTrue("double-tap did not zoom once turned on again", page.isZoomed)
        assertSame(adapter, c.get().pageAdapter)
    }

    @Test
    fun momentumScrollingFollowsItsSettingInPlace() {
        val c = launch()
        val adapter = c.get().pageAdapter
        assertTrue(c.get().pages().all { it.flingEnabled })
        c.away { prefs.fling = false }
        assertSame(adapter, c.get().pageAdapter)
        assertFalse(c.get().pages().any { it.flingEnabled })
    }

    @Test
    fun brightnessGestureFollowsItsSettingInPlace() {
        val c = launch()
        val adapter = c.get().pageAdapter
        assertTrue(c.get().pages().all { it.verticalDrag != null })
        prefs.brightnessGesture = false
        idle()
        assertSame(adapter, c.get().pageAdapter)
        assertTrue(c.get().pages().all { it.verticalDrag == null })
        c.away { prefs.brightnessGesture = true }
        assertTrue(c.get().pages().all { it.verticalDrag != null })
    }

    // ---------------------------------------------------------------- reader layout

    @Test
    fun fitAndPageGapApplyWhileReading() {
        val viewer = launch().get()
        val adapter = viewer.pageAdapter!!
        assertEquals(ZoomImageView.FitMode.SCREEN, adapter.fitMode)
        prefs.fit = "width"
        prefs.pageSpacing = 16
        idle()
        assertSame(adapter, viewer.pageAdapter)
        assertEquals(ZoomImageView.FitMode.WIDTH, adapter.fitMode)
        assertEquals((16 * viewer.resources.displayMetrics.density).toInt(), adapter.spacingPx)
    }

    @Test
    fun continuousLayoutChosenInSettingsShowsOnReturn() {
        val c = launch()
        val vertical = c.get().findViewById<RecyclerView>(R.id.viewer_vertical)
        assertEquals(View.GONE, vertical.visibility)
        // Continuous reads top to bottom only.
        c.away {
            prefs.readingDir = "ttb"
            prefs.pageMode = "continuous"
        }
        assertEquals(View.VISIBLE, vertical.visibility)
        assertEquals(View.GONE, c.get().findViewById<View>(R.id.viewer_pager).visibility)
    }

    @Test
    fun readAheadChangedInSettingsRebuildsThePages() {
        val c = launch()
        val before = c.get().pageAdapter
        c.away { prefs.preload = 0 }
        assertNotNull(c.get().pageAdapter)
        assertTrue("built anew for the new read-ahead", before !== c.get().pageAdapter)
        // Away and back with nothing changed: the pages stay as they are.
        val now = c.get().pageAdapter
        c.away {}
        assertSame(now, c.get().pageAdapter)
    }

    // ---------------------------------------------------------------- AI

    /** Counts the adapter's whole-range rebinds: [PageAdapter.aiChanged] rebinds every item. */
    private class Rebinds(private val adapter: RecyclerView.Adapter<*>) : RecyclerView.AdapterDataObserver() {
        var count = 0
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
            if (positionStart == 0 && itemCount == adapter.itemCount) count++
        }
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = onItemRangeChanged(positionStart, itemCount, null)
    }

    @Test
    fun aiTurnedOnInSettingsProcessesThePagesShownOnReturn() {
        val c = launch()
        val adapter = c.get().pageAdapter!!
        val rebinds = Rebinds(adapter).also { adapter.registerAdapterDataObserver(it) }
        c.away { prefs.aiEnabled = true }
        // The same pages, bound again to go through the chain now chosen.
        assertSame(adapter, c.get().pageAdapter)
        assertEquals(1, rebinds.count)
        c.away { prefs.aiEnabled = false }
        assertEquals(2, rebinds.count)
    }

    @Test
    fun aiDotTurnedOnInSettingsShowsOnReturn() {
        val c = launch()
        val dot = c.get().findViewById<View>(R.id.ai_dot)
        assertEquals(View.GONE, dot.visibility)
        c.away { prefs.aiDot = true }
        assertEquals(View.INVISIBLE, dot.visibility)
    }

    // ---------------------------------------------------------------- settings the feed missed

    @Test
    fun aChangeTheSettingsFeedMissedShowsOnReturn() {
        val c = launch()
        val adapter = c.get().pageAdapter!!
        muteSettingsFeed(context)
        c.away {
            prefs.fit = "width"
            prefs.doubleTapZoom = false
        }
        assertSame(adapter, c.get().pageAdapter)
        assertEquals(ZoomImageView.FitMode.WIDTH, adapter.fitMode)
        assertFalse(c.get().pages().any { it.doubleTapEnabled })
    }

    // ---------------------------------------------------------------- the library replaced

    /** The page shown, as the seek bar has it: one page a step in these books. */
    private fun ViewerActivity.page(): Int = findViewById<SeekBar>(R.id.viewer_seek).progress

    @Test
    fun aLibraryClearedInSettingsDropsTheOpenBooksOwnSettingsOnReturn() {
        val book = book()
        Library(context).setHiddenPages(ref(book), setOf(1), 3)
        // Shown page 1 is book page 2.
        val c = launch(book, page = 1)
        assertEquals(2, c.get().pageAdapter!!.itemCount)
        assertEquals(1, c.get().page())
        c.away { Library(context).clearLibraryData() }
        idleUntil("the book open again") { c.get().openSettled() }
        assertEquals(3, c.get().pageAdapter!!.itemCount)
        // Still book page 2, now with no page hidden.
        assertEquals(2, c.get().page())
        // The book's own settings stay cleared.
        assertTrue(Library(context).hiddenPages(ref(book).id).isEmpty())
    }

    @Test
    fun aLibraryRestoredInSettingsGivesTheOpenBookItsRestoredSettingsOnReturn() {
        val book = book()
        val library = Library(context)
        library.setHiddenPages(ref(book), setOf(0), 3)
        val backup = library.exportAll()
        library.setHiddenPages(ref(book), emptySet(), 3)
        val c = launch(book, page = 1)
        assertEquals(3, c.get().pageAdapter!!.itemCount)
        assertEquals(1, c.get().page())
        c.away { Library(context).importAll(backup) }
        idleUntil("the book open again") { c.get().openSettled() }
        assertEquals(2, c.get().pageAdapter!!.itemCount)
        // Still book page 1: shown page 0 with page 0 hidden.
        assertEquals(0, c.get().page())
    }

    /** The reader gone with its state kept, as on a language change or process death under Settings. */
    private fun ActivityController<ViewerActivity>.gone(): Bundle {
        val state = Bundle()
        pause().stop().saveInstanceState(state).destroy()
        controller = null
        return state
    }

    @Test
    fun aReaderRecreatedAfterALibraryClearOpensAtTheBookPageItShowed() {
        val book = book()
        Library(context).setHiddenPages(ref(book), setOf(1), 3)
        // Shown page 1 is book page 2.
        val first = launch(book, page = 1)
        assertEquals(1, first.get().page())
        val state = first.gone()
        Library(context).clearLibraryData()
        val c = launch(book, page = 1, state = state)
        assertEquals(3, c.get().pageAdapter!!.itemCount)
        assertEquals(2, c.get().page())
    }

    @Test
    fun aReaderRecreatedWhileOpeningAgainLandsWhereItWasAsked() {
        val book = book()
        Library(context).setHiddenPages(ref(book), setOf(1), 3)
        val first = launch(book, page = 1)
        assertEquals(1, first.get().page())
        first.pause().stop()
        Library(context).clearLibraryData()
        // Back from Settings: the book starts opening again at book page 2, and the reader goes before it lands.
        first.start()
        assertFalse("the book still opening", first.get().openSettled())
        val state = first.gone()
        val c = launch(book, page = 1, state = state)
        assertEquals(3, c.get().pageAdapter!!.itemCount)
        assertEquals(2, c.get().page())
    }

    @Test
    fun aPageCarriedForOneBookIsNotAppliedToAnotherOpenedMeanwhile() {
        val book = book()
        val other = Fixtures.zip(File(tmp.newFolder("other"), "Book B.cbz"), *(0 until 3).map { Fixtures.entry("p%02d.png".format(it), byteArrayOf(0)) }.toTypedArray())
        Library(context).setHiddenPages(ref(book), setOf(1), 3)
        val c = launch(book, page = 1)
        c.pause().stop()
        Library(context).clearLibraryData()
        // Back from Settings: the book starts opening again at book page 2, then another book replaces it.
        c.start()
        assertFalse("the book still opening", c.get().openSettled())
        c.newIntent(Intent(context, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_PATH, other.absolutePath))
        c.resume()
        idleUntil("the other book open") { c.get().openSettled() && c.get().pageAdapter != null }
        assertEquals(other.absolutePath, c.get().intent.getStringExtra(ViewerActivity.EXTRA_PATH))
        assertEquals(0, c.get().page())
    }

    /** Japanese page names, stored in Shift_JIS; each page's one byte is its place here. */
    private val japanese = listOf("表紙.jpg", "目次.jpg", "第一話.jpg", "第二話.jpg", "あとがき.jpg", "奥付.jpg", "裏表紙.jpg", "001.jpg")

    private fun japaneseBook(): File {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out, Charset.forName("Shift_JIS")).use { zip ->
            for ((i, n) in japanese.withIndex()) {
                zip.putNextEntry(ZipEntry(n))
                zip.write(i)
                zip.closeEntry()
            }
        }
        return File(tmp.newFolder("shelf"), "Book J.cbz").apply { writeBytes(out.toByteArray()) }
    }

    /** The place in [japanese] of each page of [file] with names read in [charsetName], in page order. */
    private fun order(file: File, charsetName: String): List<Int> =
        ArchiveSource(ZipReader(file, charsetName)).use { src -> (0 until src.pageCount).map { i -> src.readPage(i) { it.read() } } }

    @Test
    fun aLibraryRestoredWithTheBooksOwnNameCharsetReopensItAtItsPage() {
        val book = japaneseBook()
        val before = order(book, NameCharset.AUTO)
        val after = order(book, "windows-1251")
        // A page the other charset moves.
        val page = before.indices.first { before[it] != after[it] }
        val library = Library(context)
        library.setNameCharset(ref(book), "windows-1251")
        val backup = library.exportAll()
        library.setNameCharset(ref(book), null)
        val c = launch(book, page = page)
        assertEquals(page, c.get().page())
        c.away { Library(context).importAll(backup) }
        idleUntil("the book open again") { c.get().openSettled() }
        assertEquals(after.indexOf(before[page]), c.get().page())
    }

    @Test
    fun aReaderRecreatedAfterARestoreWithTheBooksOwnNameCharsetOpensAtItsPage() {
        val book = japaneseBook()
        val before = order(book, NameCharset.AUTO)
        val after = order(book, "windows-1251")
        val page = before.indices.first { before[it] != after[it] }
        val library = Library(context)
        library.setNameCharset(ref(book), "windows-1251")
        val backup = library.exportAll()
        library.setNameCharset(ref(book), null)
        val first = launch(book, page = page)
        assertEquals(page, first.get().page())
        val state = first.gone()
        Library(context).importAll(backup)
        val c = launch(book, page = page, state = state)
        assertEquals(after.indexOf(before[page]), c.get().page())
    }

    @Test
    fun aNameCharsetChangedInSettingsKeepsThePageShown() {
        val book = japaneseBook()
        val before = order(book, NameCharset.AUTO)
        val after = order(book, "windows-1251")
        val page = before.indices.first { before[it] != after[it] }
        val c = launch(book, page = page)
        assertEquals(page, c.get().page())
        c.away { prefs.charset = "windows-1251" }
        idleUntil("the book open again") { c.get().openSettled() }
        assertEquals(after.indexOf(before[page]), c.get().page())
    }
}
