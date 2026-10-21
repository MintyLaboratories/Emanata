package com.infinity.comicreader.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.materialswitch.MaterialSwitch
import com.infinity.comicreader.R
import com.infinity.comicreader.data.BookFacts
import com.infinity.comicreader.data.BookFactsCache
import com.infinity.comicreader.data.FolderLayout
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ReaderLayout
import com.infinity.comicreader.data.ResumeMode
import com.infinity.comicreader.reader.BookDirection
import com.infinity.comicreader.reader.DirectionDetection
import com.infinity.comicreader.reader.Fixtures
import com.infinity.comicreader.reader.SyntheticStrips
import com.infinity.comicreader.reader.TestImages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

/** Books of slices of one strip read top to bottom in one column, unless the folder says otherwise. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViewerActivitySlicesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var prefs: Prefs
    private lateinit var library: Library
    private lateinit var dir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("library", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        prefs = Prefs(context)
        prefs.resumeMode = ResumeMode.NO
        library = Library(context)
        dir = tmp.newFolder("shelf")
    }

    /** Slices of varying heights: their shape alone tells. */
    private fun shapedBook(): File =
        Fixtures.zip(File(dir, "Strip 01.cbz"), *listOf(300, 380, 260, 450, 320).mapIndexed { i, h -> Fixtures.entry("p%02d.png".format(i), TestImages.png(100, h)) }.toTypedArray())

    /** Slices of one height cut from one picture: only their seams tell. */
    private fun seamedBook(): File {
        val width = 240
        val heights = List(7) { 720 }
        val pages = SyntheticStrips.cut(SyntheticStrips.strip(width, heights.sum(), seed = 31), width, heights).map { pixels ->
            val bitmap = Bitmap.createBitmap(pixels, width, pixels.size / width, Bitmap.Config.ARGB_8888)
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }
        return Fixtures.zip(File(dir, "Strip 02.cbz"), *pages.mapIndexed { i, p -> Fixtures.entry("p%02d.png".format(i), p) }.toTypedArray())
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun launch(book: File): ViewerActivity {
        val intent = Intent(ApplicationProvider.getApplicationContext(), ViewerActivity::class.java)
            .putExtra(ViewerActivity.EXTRA_PATH, book.absolutePath)
        val viewer = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup().get()
        val progress = viewer.findViewById<View>(R.id.viewer_progress)
        val deadline = System.currentTimeMillis() + 5_000
        do {
            Thread.sleep(10)
            idle()
        } while (progress.visibility == View.VISIBLE && System.currentTimeMillis() < deadline)
        return viewer
    }

    /** Waits up to [ms] for [done]. */
    private fun await(ms: Long = 5_000, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + ms
        while (!done() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            idle()
        }
    }

    private fun ViewerActivity.continuous() = findViewById<View>(R.id.viewer_vertical).visibility == View.VISIBLE

    private fun ViewerActivity.hint() = findViewById<TextView>(R.id.viewer_hint)

    @Test
    fun slicesReadInOneColumnFromTheFirstPage() {
        val viewer = launch(shapedBook())
        assertEquals(true, viewer.continuous())
        assertEquals("Top-to-bottom (from page shapes)", viewer.hint().text.toString())
        // The defaults stay as they were.
        assertEquals("paged", prefs.pageMode)
        assertEquals("ltr", prefs.readingDir)
    }

    @Test
    fun slicesOfOneHeightReadInOneColumnOnceTheirSeamsTell() {
        val viewer = launch(seamedBook())
        await { viewer.continuous() }
        assertEquals(true, viewer.continuous())
        assertEquals("Top-to-bottom (from page shapes and seams)", viewer.hint().text.toString())
    }

    @Test
    fun seamsFoundOnceShowTheNextOpenInOneColumnAtOnce() {
        val book = seamedBook()
        val first = launch(book)
        await { first.continuous() }
        assertEquals(DirectionDetection.Source.SEAMS, BookDirection.checkedSlices(book)?.found?.source)
        first.finish()
        idle()
        val again = launch(book)
        assertEquals(true, again.continuous())
        assertEquals("Top-to-bottom (from page shapes and seams)", again.hint().text.toString())
    }

    @Test
    fun seamsFoundAreKeptWithTheLibrarysFacts() {
        val book = seamedBook()
        val facts = BookFactsCache(ApplicationProvider.getApplicationContext())
        facts.put(book.absolutePath, book.length(), BookFacts(7, 0))
        val viewer = launch(book)
        await { viewer.continuous() }
        assertEquals(DirectionDetection.Source.SEAMS, facts.get(book.absolutePath, book.length())?.slices?.found?.source)
    }

    @Test
    fun turningAPageWhileSeamsAreCheckedStillShowsOneColumn() {
        val viewer = launch(seamedBook())
        viewer.findViewById<ViewPager2>(R.id.viewer_pager).setCurrentItem(1, false)
        idle()
        await { viewer.continuous() }
        assertEquals(true, viewer.continuous())
    }

    @Test
    fun aFolderDirectionIsNeverOverridden() {
        library.setFolderLayout(dir.absolutePath, FolderLayout.Direction("ltr"))
        val viewer = launch(shapedBook())
        val pager = viewer.findViewById<ViewPager2>(R.id.viewer_pager)
        assertEquals(View.VISIBLE, pager.visibility)
        assertEquals(ViewPager2.ORIENTATION_HORIZONTAL, pager.orientation)
        assertNotEquals(View.VISIBLE, viewer.hint().visibility)
    }

    @Test
    fun aFolderReadTopToBottomShowsSlicesInOneColumn() {
        library.setFolderLayout(dir.absolutePath, FolderLayout.Direction("ttb"))
        val viewer = launch(shapedBook())
        assertEquals(true, viewer.continuous())
        // The folder decided the direction: nothing to note.
        assertNotEquals(View.VISIBLE, viewer.hint().visibility)
    }

    @Test
    fun aFrozenLayoutKeepsItsPages() {
        library.setFolderLayout(dir.absolutePath, FolderLayout.Frozen(ReaderLayout(dir = "ttb")))
        val viewer = launch(shapedBook())
        assertEquals(View.VISIBLE, viewer.findViewById<View>(R.id.viewer_pager).visibility)
    }

    @Test
    fun detectionOffPagesAsTheDefaultsSay() {
        prefs.detectDirection = false
        val viewer = launch(shapedBook())
        assertEquals(false, viewer.continuous())
    }

    @Test
    fun pickingPagedPagesTopToBottom() {
        val viewer = launch(shapedBook())
        ReaderSheet(viewer).tap(R.id.btn_mode_paged)
        val pager = viewer.findViewById<ViewPager2>(R.id.viewer_pager)
        assertEquals(View.VISIBLE, pager.visibility)
        assertEquals(ViewPager2.ORIENTATION_VERTICAL, pager.orientation)
    }

    @Test
    fun autoHandsTheFolderBackToSlices() {
        library.setFolderLayout(dir.absolutePath, FolderLayout.Direction("ltr"))
        val viewer = launch(shapedBook())
        assertEquals(false, viewer.continuous())
        ReaderSheet(viewer).tap(R.id.btn_dir_auto)
        await { viewer.continuous() }
        assertEquals(true, viewer.continuous())
        assertEquals(null, library.folderLayout(dir.absolutePath))
    }

    @Test
    fun aDirectionPickedWithoutAFolderStays() {
        prefs.perSeries = false
        val viewer = launch(shapedBook())
        assertEquals(true, viewer.continuous())
        ReaderSheet(viewer).tap(R.id.btn_dir_rtl)
        await(500) { false }
        val pager = viewer.findViewById<ViewPager2>(R.id.viewer_pager)
        assertEquals(false, viewer.continuous())
        assertEquals(View.VISIBLE, pager.visibility)
        assertEquals(ViewPager2.ORIENTATION_HORIZONTAL, pager.orientation)
        assertEquals("rtl", prefs.readingDir)
    }

    @Test
    fun aDirectionPickedForTheFolderStays() {
        val viewer = launch(shapedBook())
        ReaderSheet(viewer).tap(R.id.btn_dir_rtl)
        idle()
        assertEquals(false, viewer.continuous())
        assertEquals(FolderLayout.Direction("rtl"), library.folderLayout(dir.absolutePath))
    }

    private fun ViewerActivity.columnCrops() = (findViewById<RecyclerView>(R.id.viewer_vertical).adapter as PageAdapter).autoCrop

    @Test
    fun slicesAreNeverAutoCropped() {
        prefs.autoCrop = true
        val viewer = launch(shapedBook())
        assertEquals(true, viewer.continuous())
        assertEquals(false, viewer.columnCrops())
        assertEquals(false, ReaderSheet(viewer).view<MaterialSwitch>(R.id.sheet_auto_crop).isEnabled)
        assertEquals(true, prefs.autoCrop)
    }

    @Test
    fun aChosenColumnAutoCropsOrdinaryPages() {
        prefs.autoCrop = true
        prefs.pageMode = "continuous"
        prefs.readingDir = "ttb"
        val book = Fixtures.zip(File(dir, "Book 01.cbz"), *(0 until 5).map { Fixtures.entry("p%02d.png".format(it), TestImages.png(200, 300)) }.toTypedArray())
        val viewer = launch(book)
        assertEquals(true, viewer.continuous())
        assertEquals(true, viewer.columnCrops())
        assertEquals(true, ReaderSheet(viewer).view<MaterialSwitch>(R.id.sheet_auto_crop).isEnabled)
    }

    @Test
    fun ordinaryPagesPageAsBefore() {
        val book = Fixtures.zip(File(dir, "Book 01.cbz"), *(0 until 5).map { Fixtures.entry("p%02d.png".format(it), TestImages.png(200, 300)) }.toTypedArray())
        val viewer = launch(book)
        assertEquals(false, viewer.continuous())
        assertNotEquals(View.VISIBLE, viewer.hint().visibility)
    }
}
