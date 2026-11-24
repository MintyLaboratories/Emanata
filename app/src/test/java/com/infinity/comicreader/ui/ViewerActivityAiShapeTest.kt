package com.infinity.comicreader.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.View
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager2.widget.ViewPager2
import com.infinity.comicreader.R
import com.infinity.comicreader.ai.AiOutcome
import com.infinity.comicreader.ai.ChainKind
import com.infinity.comicreader.ai.OfflineDownloads
import com.infinity.comicreader.ai.PageClass
import com.infinity.comicreader.ai.PageEnhancer
import com.infinity.comicreader.ai.ReaderAi
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ResumeMode
import com.infinity.comicreader.reader.Fixtures
import com.infinity.comicreader.reader.PageSource
import com.infinity.comicreader.reader.Target
import com.infinity.comicreader.reader.TestImages
import com.infinity.comicreader.testing.Waits
import com.infinity.comicreader.testing.idleUntil
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import java.io.File
import java.time.Duration
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A new shape of the open book's pages processes anew only the pages it reads otherwise; the page under way goes on. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViewerActivityAiShapeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** AI on asks for the default upscaler: kept off the network. */
    @get:Rule
    val downloads = OfflineDownloads()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var prefs: Prefs

    /** Pages asked for, in order, by every adapter the reader made. */
    private val asked: MutableList<Int> = Collections.synchronizedList(ArrayList())

    /** What each page's work was told to stop on, by page, as last asked. */
    private val canceled = ConcurrentHashMap<Int, () -> Boolean>()

    /** Pages whose work waits until their latch opens. */
    private val held = ConcurrentHashMap<Int, CountDownLatch>()

    /** Pages processed in the reader's place, its AI judging which go through AI and which a new shape changes. */
    private inner class Work(private val ai: ReaderAi, override val worker: CoroutineDispatcher) : PageEnhancer {
        override fun enhances(source: PageSource): Boolean = ai.enhances(source)

        override fun enhance(
            source: PageSource, index: Int, target: Target, width: Int, height: Int, held: () -> Boolean, models: () -> Unit,
            detailBytes: Long, remake: Boolean, canceled: () -> Boolean
        ): AiOutcome {
            asked += index
            this@ViewerActivityAiShapeTest.canceled[index] = canceled
            models()
            this@ViewerActivityAiShapeTest.held[index]?.await(Waits.LIVENESS_MS, TimeUnit.MILLISECONDS)
            return AiOutcome.Done(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(PROCESSED) })
        }
    }

    @Before
    fun setUp() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("library", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        prefs = Prefs(context)
        prefs.resumeMode = ResumeMode.NO
        prefs.detectDirection = false
        prefs.aiEnabled = true
        ViewerActivity.enhancerOf = { ai -> Work(ai, executor.asCoroutineDispatcher()) }
    }

    @After
    fun tearDown() {
        ViewerActivity.enhancerOf = { it }
        held.values.forEach { it.countDown() }
        executor.shutdownNow()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun open(book: File): ViewerActivity {
        val intent = Intent(context, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_PATH, book.absolutePath)
        val viewer = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup().get()
        val progress = viewer.findViewById<View>(R.id.viewer_progress)
        idleUntil("the book open") { progress.visibility != View.VISIBLE }
        return viewer
    }

    /** The strip's page at [position] shows as processed: in [PROCESSED]. */
    private fun shownProcessed(strip: RecyclerView, position: Int): Boolean =
        ((strip.findViewHolderForAdapterPosition(position) as? PageAdapter.PageHolder)?.images?.get(0)?.drawable as? BitmapDrawable)?.bitmap
            ?.let { it.getPixel(it.width / 2, it.height / 2) == PROCESSED } == true

    private fun pages(vararg widths: Int) = widths.mapIndexed { i, w -> Fixtures.entry("p%02d.png".format(i), TestImages.png(w, 1000)) }.toTypedArray()

    /** Counts the adapter's whole-range rebinds: [PageAdapter.aiChanged] rebinds every item. */
    private class Rebinds(private val adapter: RecyclerView.Adapter<*>) : RecyclerView.AdapterDataObserver() {
        var count = 0
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
            if (positionStart == 0 && itemCount == adapter.itemCount) count++
        }
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = onItemRangeChanged(positionStart, itemCount, null)
    }

    @Test
    fun learnedSizesMovingTheMedianLeaveThePageUnderWayGoing() {
        // A solid RAR: sizes are learned as pages load, a round after the first.
        val book = File(tmp.newFolder(), "Sample v01.cbr").apply { writeBytes(Fixtures.rar4Bytes(*pages(650, 650, 670), solid = true)) }
        held[1] = CountDownLatch(1)
        val viewer = open(book)
        idleUntil("page 0 processed, page 1 under way") { asked.toList() == listOf(0, 1) }
        assertNull("no size learned yet", viewer.aiShapeRun()?.aspect)
        val adapter = viewer.findViewById<ViewPager2>(R.id.viewer_pager).adapter!!
        val rebinds = Rebinds(adapter).also { adapter.registerAdapterDataObserver(it) }

        // The round learning the pages' sizes: their median aspect becomes known, 0.65 or 0.66.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        idleUntil("the sizes learned") { viewer.aiShapeRun()?.aspect != null }
        assertEquals(PageClass.US, viewer.aiShapeRun()?.pageClass)
        // Both pages print alike under it: nothing shows anew and page 1 goes on.
        assertEquals(0, rebinds.count)
        assertFalse("page 1 goes on", canceled.getValue(1)())
    }

    @Test
    fun turningABookRightToLeftProcessesItsPagesAnewAtB6() {
        val book = Fixtures.zip(File(tmp.newFolder(), "Sample v02.cbz"), *pages(650, 650, 650))
        // The folder keeps its chain: only the shape changes with the direction.
        Library(context).setFolderChainKind(book.parent!!, ChainKind.OTHER)
        val viewer = open(book)
        idleUntil("pages 0 and 1 processed, the open settled") { asked.toList().containsAll(listOf(0, 1)) && viewer.openSettled() }
        assertEquals(PageClass.US, viewer.aiShapeRun()?.pageClass)
        val before = asked.toList().count { it == 0 }
        ReaderSheet(viewer).tap(R.id.btn_dir_rtl)
        assertEquals(ChainKind.OTHER, viewer.aiKindRun())
        assertEquals("read at B6", PageClass.MANGA, viewer.aiShapeRun()?.pageClass)
        idleUntil("page 0 processed anew") { asked.toList().count { it == 0 } > before }
        idle()
    }

    @Test
    fun rollingIntoAChapterMarkedMangaReadsItAtB6WithoutProcessingItAnew() {
        // A strip, which lays the next chapter in behind this one.
        prefs.pageMode = "continuous"
        prefs.readingDir = "ttb"
        prefs.autoNext = true
        val dir = tmp.newFolder()
        val first = Fixtures.zip(File(dir, "Sample v01.cbz"), *pages(650, 650, 650))
        val info = Fixtures.entry("ComicInfo.xml", "<?xml version=\"1.0\"?><ComicInfo><Manga>Yes</Manga></ComicInfo>".toByteArray())
        // Pages of their own aspect, 0.70: told once they are found, after it is the open book.
        val second = Fixtures.zip(File(dir, "Sample v02.cbz"), *pages(700, 700, 700), info)
        val viewer = open(first)
        val strip = viewer.findViewById<RecyclerView>(R.id.viewer_vertical)
        fun openPath() = viewer.intent.getStringExtra(ViewerActivity.EXTRA_PATH)
        val lm = strip.layoutManager as LinearLayoutManager
        val scroll = { strip.scrollBy(0, strip.height / 2); idle() }
        idleUntil("the second chapter laid in behind") { (strip.adapter?.itemCount ?: 0) >= 6 }
        assertEquals(PageClass.WEB, viewer.aiShapeRun()?.pageClass)

        // Its first page, item 3, on screen and processed while the first chapter is still the open book.
        idleUntil("its first page on screen", step = scroll) { lm.findLastVisibleItemPosition() >= 3 }
        assertEquals(2, lm.findFirstVisibleItemPosition())
        idleUntil("its first page shown processed") { shownProcessed(strip, 3) }
        assertEquals(first.absolutePath, openPath())
        val rebinds = Rebinds(strip.adapter!!).also { strip.adapter!!.registerAdapterDataObserver(it) }

        // Scrolled until it starts the strip, which posts the roll-on (it renumbers the items).
        idleUntil("its first page at the top", step = scroll) { lm.findFirstVisibleItemPosition() >= 3 || openPath() == second.absolutePath }
        idleUntil("the second chapter open") { openPath() == second.absolutePath }
        idleUntil("its own pages' shape told") { viewer.aiShapeRun()?.aspect == 0.70 }
        assertEquals("read at B6", PageClass.MANGA, viewer.aiShapeRun()?.pageClass)
        // Its pages already processed for B6 stay so: none shown plain again.
        assertEquals(0, rebinds.count)
        assertTrue("its first page still shown processed", shownProcessed(strip, 0))
    }

    private companion object {
        /** The color of a page processed in the reader's place. */
        val PROCESSED = Color.rgb(10, 120, 30)
    }
}
