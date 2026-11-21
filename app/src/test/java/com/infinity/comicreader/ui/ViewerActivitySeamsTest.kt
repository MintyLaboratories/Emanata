package com.infinity.comicreader.ui

import com.infinity.comicreader.testing.ref
import com.infinity.comicreader.testing.bookId
import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager2.widget.ViewPager2
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ResumeMode
import com.infinity.comicreader.data.SpreadDisplay
import com.infinity.comicreader.data.TwoPage
import com.infinity.comicreader.data.WidePages
import com.infinity.comicreader.reader.Fixtures
import com.infinity.comicreader.reader.SyntheticArt
import com.infinity.comicreader.testing.awaitJob
import com.infinity.comicreader.testing.idleUntil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration

/** Spreads in two files, told by their seams: shown as one, or paired, in place of the pairing by number. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViewerActivitySeamsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var prefs: Prefs
    private lateinit var dir: File

    private val width = 200
    private val height = 300

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("library", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        prefs = Prefs(context)
        prefs.resumeMode = ResumeMode.NO
        dir = tmp.newFolder("shelf")
    }

    private fun encoded(pixels: IntArray, png: Boolean): ByteArray {
        val bitmap = Bitmap.createBitmap(pixels, width, pixels.size / width, Bitmap.Config.ARGB_8888)
        val format = if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        return ByteArrayOutputStream().also { bitmap.compress(format, 100, it) }.toByteArray()
    }

    /**
     * [count] pages `Tale 001-000`…, each its own picture, but pages [first] and the next the halves of one;
     * a [solid] archive is read in order only, its page sizes learned as pages load. JPEG pages, or [png]
     * ones, whose sides are read only from the reader's decodes.
     */
    private fun book(count: Int, first: Int, solid: Boolean = false, png: Boolean = solid): File {
        val art = SyntheticArt.art(width * 2, height, seed = 77)
        val pages = (0 until count).map { i ->
            when (i) {
                first -> SyntheticArt.columns(art, width * 2, 0, width)
                first + 1 -> SyntheticArt.columns(art, width * 2, width, width * 2)
                else -> SyntheticArt.art(width, height, seed = i.toLong())
            }
        }
        val ext = if (png) "png" else "jpg"
        val entries = pages.mapIndexed { i, p -> Fixtures.entry("Tale 001-%03d.$ext".format(i), encoded(p, png)) }.toTypedArray()
        if (solid) return File(dir, "Tale 001.cbr").apply { writeBytes(Fixtures.rar4Bytes(*entries, solid = true)) }
        return Fixtures.zip(File(dir, "Tale 001.cbz"), *entries)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun launch(book: File): ViewerActivity {
        val intent = Intent(ApplicationProvider.getApplicationContext(), ViewerActivity::class.java)
            .putExtra(ViewerActivity.EXTRA_PATH, book.absolutePath)
        val viewer = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup().get()
        val progress = viewer.findViewById<View>(R.id.viewer_progress)
        await("the book open") { progress.visibility != View.VISIBLE }
        return viewer
    }

    /** Idles until [done], named [what], a frame's time passing each round: scans start once the first page is drawn. */
    private fun await(what: String, done: () -> Boolean) =
        idleUntil(what, step = { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }, done = done)

    /**
     * Waits until no scan of the book runs and no page decodes: whatever they would change has
     * changed. With [release], first past the scans' two-second hold, so they have started even
     * with no page drawn.
     */
    private fun ViewerActivity.settle(release: Boolean = false) {
        if (release) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        idleUntil("the book's scans done") { openSettled() }
    }

    /** Waits until every pages-seen write queued so far is done. */
    private fun seenWritten() = awaitJob("the pages seen written", CoroutineScope(ViewerActivity.seenIo).launch {})

    private fun ViewerActivity.items() = findViewById<ViewPager2>(R.id.viewer_pager).adapter!!.itemCount

    private fun ViewerActivity.indicator() = findViewById<TextView>(R.id.viewer_page_text).text.toString()

    private fun ViewerActivity.next() {
        findViewById<View>(R.id.btn_next_page).performClick()
        idle()
    }

    private fun ViewerActivity.previous() {
        findViewById<View>(R.id.btn_prev_page).performClick()
        idle()
    }

    @Test
    fun aSpreadInTwoFilesShowsAsOneAndTurnsTheScreen() {
        prefs.widePages = WidePages.ORIENTATION
        val viewer = launch(book(count = 7, first = 3))
        // Merge is the default: pages 4 and 5 become one screen once their seam is checked.
        await("the spread's halves joined") { viewer.items() == 6 }
        assertEquals(6, viewer.items())
        repeat(3) { viewer.next() }
        assertEquals("4 / 7", viewer.indicator())
        // Change orientation picked: the screen turns for the spread.
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, viewer.requestedOrientation)
        viewer.next()
        assertEquals("6 / 7", viewer.indicator())
        assertNotEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, viewer.requestedOrientation)
    }

    @Test
    fun splitKeepsTwoFilesApart() {
        prefs.spreads = SpreadDisplay.SPLIT
        val viewer = launch(book(count = 7, first = 3))
        viewer.settle(release = true)
        assertEquals(7, viewer.items())
    }

    @Test
    fun withoutJoiningSpreadsSeamsAreNotRead() {
        prefs.joinSpreads = false
        val viewer = launch(book(count = 7, first = 3))
        viewer.settle(release = true)
        assertEquals(7, viewer.items())
        val seams = File(ApplicationProvider.getApplicationContext<Application>().cacheDir, "page-seams-by-id")
        assertTrue(seams.listFiles().isNullOrEmpty())
    }

    @Test
    fun theSheetUndoesAJoinAndBringsItBack() {
        val viewer = launch(book(count = 7, first = 3))
        await("the spread's halves joined") { viewer.items() == 6 }
        val sheet = ReaderSheet(viewer)
        sheet.tap(R.id.sheet_join_spreads)
        assertEquals(false, prefs.joinSpreads)
        assertEquals(7, viewer.items())
        sheet.tap(R.id.sheet_join_spreads)
        await("the spread's halves joined") { viewer.items() == 6 }
        assertEquals(6, viewer.items())
    }

    @Test
    fun twoPagesPairTheHalvesOfOnePicture() {
        prefs.twoPage = TwoPage.ON
        // By number: [000] [001|002] [003|004] [005|006] [007|008]; page 004 runs on into 005.
        val viewer = launch(book(count = 9, first = 4))
        await("the spread's halves joined") { viewer.items() == 6 }
        assertEquals(6, viewer.items())
        repeat(3) { viewer.next() }
        assertEquals("5 / 9", viewer.indicator())
    }

    @Test
    fun aLoneMatchRePairsOnlyThePagesAfterTheReaderOnceNear() {
        prefs.twoPage = TwoPage.ON
        // By number: [000] [001|002] … [029|030], 16 screens; page 020 running on into 021 pairs
        // even left pages instead.
        val book = book(count = 31, first = 20)
        val viewer = launch(book)
        viewer.next()
        assertEquals("2 / 31", viewer.indicator())
        val seams = File(ApplicationProvider.getApplicationContext<Application>().cacheDir, "page-seams-by-id")
        await("seams stored") { seams.listFiles()?.any { it.name.endsWith(".bin") } == true }
        viewer.settle()
        // One match far from the reader settles nothing.
        assertEquals(16, viewer.items())
        // On [017|018] the match is near: that screen and those before it stay, the pages after
        // re-pair: [019] [020|021] … [028|029] [030].
        repeat(8) { viewer.next() }
        assertEquals("18 / 31", viewer.indicator())
        await("the pages after re-paired") { viewer.items() == 17 }
        assertEquals(17, viewer.items())
        assertEquals("18 / 31", viewer.indicator())
        viewer.next()
        assertEquals("20 / 31", viewer.indicator())
        viewer.next()
        assertEquals("21 / 31", viewer.indicator())
        // The pages seen are stored a while after the last new one.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        await("pages seen stored") { seams.listFiles()?.any { it.name.endsWith(".seen") } == true }
        viewer.finish()
        // Opened again at the start, the pages seen keep the run settled.
        val again = launch(book)
        // Laid out again once the first page shows and the sizes scan.
        await("the pages after re-paired") { again.items() == 17 }
        assertEquals(17, again.items())
        assertEquals("1 / 31", again.indicator())
    }

    @Test
    fun aLoneMatchFarFromPagesSeenStaysUnsettledWhenOpenedAgain() {
        prefs.twoPage = TwoPage.ON
        val book = book(count = 31, first = 20)
        val viewer = launch(book)
        viewer.next()
        val seams = File(ApplicationProvider.getApplicationContext<Application>().cacheDir, "page-seams-by-id")
        await("seams stored") { seams.listFiles()?.any { it.name.endsWith(".bin") } == true }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        await("pages seen stored") { seams.listFiles()?.any { it.name.endsWith(".seen") } == true }
        viewer.finish()
        val again = launch(book)
        again.settle(release = true)
        assertEquals(16, again.items())
    }

    @Test
    fun pagesSeenAreNotStoredWhereSeamsPlayNoPart() {
        prefs.spreads = SpreadDisplay.SPLIT
        val viewer = launch(book(count = 7, first = 3))
        repeat(3) { viewer.next() }
        assertEquals("4 / 7", viewer.indicator())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        // Past the pages seen's five-second delay: stored by now, if at all.
        viewer.settle()
        seenWritten()
        val seams = File(ApplicationProvider.getApplicationContext<Application>().cacheDir, "page-seams-by-id")
        assertTrue(seams.listFiles().orEmpty().none { it.name.endsWith(".seen") })
        // Merged again, the pages seen count: they are stored.
        ReaderSheet(viewer).tap(R.id.btn_spreads_merge)
        viewer.next()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        await("pages seen stored") { seams.listFiles()?.any { it.name.endsWith(".seen") } == true }
        assertTrue(seams.listFiles().orEmpty().any { it.name.endsWith(".seen") })
    }

    @Test
    fun pageSizesLearnedLaterKeepTheScreensAlreadyRead() {
        prefs.twoPage = TwoPage.ON
        // As above, in a book whose page sizes are learned as its pages load.
        val viewer = launch(book(count = 31, first = 20, solid = true))
        repeat(9) { viewer.next() }
        assertEquals("18 / 31", viewer.indicator())
        await("the sizes learned so far laid out") { viewer.openSettled() }
        // Pages loaded from here teach their sizes; [017|018] and those before it still stay.
        viewer.next()
        assertEquals("20 / 31", viewer.indicator())
        // Two seconds a tenth at a time, each step's sizes learned and laid out before the next.
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            await("the sizes learned so far laid out") { viewer.openSettled() }
        }
        viewer.previous()
        assertEquals("18 / 31", viewer.indicator())
        viewer.previous()
        assertEquals("16 / 31", viewer.indicator())
    }

    @Test
    fun ignoringSeamsInThisBookKeepsItsPagesApart() {
        val book = book(count = 7, first = 3)
        val viewer = launch(book)
        await("the spread's halves joined") { viewer.items() == 6 }
        val sheet = ReaderSheet(viewer)
        sheet.tap(R.id.sheet_ignore_seams)
        assertEquals(7, viewer.items())
        assertTrue(com.infinity.comicreader.data.Library(viewer).ignoresSeams(bookId(book)))
        viewer.finish()
        // Kept for the book: opened again, its pages stay apart.
        val again = launch(book)
        again.settle(release = true)
        assertEquals(7, again.items())
        ReaderSheet(again).tap(R.id.sheet_ignore_seams)
        await("the spread's halves joined") { again.items() == 6 }
        assertEquals(6, again.items())
    }

    @Test
    fun ignoringSeamsWaitsWhereSeamsPlayNoPart() {
        prefs.spreads = SpreadDisplay.SPLIT
        val viewer = launch(book(count = 7, first = 3))
        val sheet = ReaderSheet(viewer)
        val ignore = sheet.view<View>(R.id.sheet_ignore_seams)
        assertFalse(ignore.isEnabled)
        assertEquals(View.VISIBLE, ignore.visibility)
        sheet.tap(R.id.btn_spreads_merge)
        assertTrue(ignore.isEnabled)
    }

    @Test
    fun ignoringSeamsPairsTwoPagesByNumberAndChecksNoSeams() {
        prefs.twoPage = TwoPage.ON
        val book = book(count = 9, first = 4)
        com.infinity.comicreader.data.Library(ApplicationProvider.getApplicationContext()).setIgnoreSeams(ref(book), true)
        val viewer = launch(book)
        viewer.settle(release = true)
        // By number: [000] [001|002] [003|004] [005|006] [007|008], though 004 runs on into 005.
        assertEquals(5, viewer.items())
        val seams = File(ApplicationProvider.getApplicationContext<Application>().cacheDir, "page-seams-by-id")
        assertTrue(seams.listFiles().orEmpty().none { it.name.endsWith(".bin") })
        // Read again, the seams pair the halves.
        ReaderSheet(viewer).tap(R.id.sheet_ignore_seams)
        await("the spread's halves joined") { viewer.items() == 6 }
        assertEquals(6, viewer.items())
    }

    @Test
    fun seamsFoundLaterAwayFromTheReaderShow() {
        val viewer = launch(book(count = 31, first = 20))
        viewer.next()
        await("the spread's halves joined") { viewer.items() == 30 }
        assertEquals(30, viewer.items())
        assertEquals("2 / 31", viewer.indicator())
    }

    @Test
    fun pngPagesAreCheckedOnlyOnceTheReaderDecodesThem() {
        val viewer = launch(book(count = 31, first = 20, png = true))
        val seams = File(ApplicationProvider.getApplicationContext<Application>().cacheDir, "page-seams-by-id")
        await("seams stored") { seams.listFiles()?.any { it.name.endsWith(".bin") } == true }
        viewer.settle()
        // Neither half is decoded yet: read for its sides alone, a PNG page would decode twice.
        assertEquals(31, viewer.items())
        // Two pages ahead, both halves are decoded for the reader: their seam is checked from those decodes.
        repeat(19) { viewer.next() }
        assertEquals("20 / 31", viewer.indicator())
        await("the spread's halves joined") { viewer.items() == 30 }
        assertEquals(30, viewer.items())
    }

    @Test
    fun seamsAreKeptForTheNextOpen() {
        val book = book(count = 7, first = 3)
        val first = launch(book)
        await("the spread's halves joined") { first.items() == 6 }
        first.finish()
        val seams = File(ApplicationProvider.getApplicationContext<Application>().cacheDir, "page-seams-by-id")
        assertTrue(seams.listFiles()?.any { it.name.endsWith(".bin") } == true)
        // Opened again, the merged screen is there from the start.
        val again = launch(book)
        assertEquals(6, again.items())
    }
}
