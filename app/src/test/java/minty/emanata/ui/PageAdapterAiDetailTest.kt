package minty.emanata.ui

import android.app.ActivityManager
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import minty.emanata.ai.AiOutcome
import minty.emanata.ai.PageEnhancer
import minty.emanata.reader.BitmapDetail
import minty.emanata.reader.PageDetail
import minty.emanata.reader.PageSource
import minty.emanata.reader.ProcessedDetail
import minty.emanata.reader.Region
import minty.emanata.reader.Scaling
import minty.emanata.reader.Target
import minty.emanata.testing.idleUntil
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** Zoomed views of processed pages draw from the model's full-size output, kept near the page read, made again when let go of. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageAdapterAiDetailTest {
    private val plain = Color.rgb(200, 200, 200)
    private val processed = Color.rgb(10, 120, 30)

    private fun filled(w: Int, h: Int, color: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    /** Plain pages, counting the plain details loaded for zooms. */
    private inner class Source(override val pageCount: Int) : PageSource {
        val detailLoads = AtomicInteger()

        override fun loadPage(index: Int, target: Target): Bitmap {
            val plan = target.plan(1000.0, 1500.0)
            return filled(plan.width, plan.height, plain)
        }

        override fun loadDetail(index: Int, target: Target, maxBytes: Long): PageDetail {
            detailLoads.incrementAndGet()
            return BitmapDetail(filled(200, 300, plain))
        }

        override fun close() {}
    }

    /** A model's output for [page]: it says when it is freed, and fails if drawn from after. */
    private class Output(val page: Int, bitmap: Bitmap) : PageDetail {
        private val inner = BitmapDetail(bitmap)

        @Volatile
        var released = false
        override val byteCount: Long get() = inner.byteCount

        override fun render(region: Region, width: Int, height: Int, linear: Boolean, scaling: Scaling): Bitmap {
            check(!released) { "drawn from after it was freed" }
            return inner.render(region, width, height, linear, scaling)
        }

        override fun release() {
            released = true
            inner.release()
        }
    }

    private data class Ask(val page: Int, val remake: Boolean, val detailBytes: Long)

    /**
     * A model stand-in: every page [color], its output at twice the size kept as its detail when
     * asked for; pages [kept] read back from disk with none, unless remade; [later] puts pages off.
     */
    private inner class Enhancer(private val color: Int) : PageEnhancer {
        // On the main looper: the work runs as the tests idle it.
        override val worker: CoroutineDispatcher = Dispatchers.Main
        val asked: MutableList<Ask> = Collections.synchronizedList(ArrayList())
        val outputs: MutableList<Output> = Collections.synchronizedList(ArrayList())
        val kept: MutableSet<Int> = Collections.synchronizedSet(HashSet())

        @Volatile
        var later = false

        override fun enhances(source: PageSource): Boolean = true

        override fun stored(source: PageSource, index: Int, target: Target, width: Int, height: Int): Boolean = index in kept

        override fun enhance(
            source: PageSource, index: Int, target: Target, width: Int, height: Int, held: () -> Boolean, models: () -> Unit,
            detailBytes: Long, remake: Boolean, canceled: () -> Boolean
        ): AiOutcome {
            asked += Ask(index, remake, detailBytes)
            if (later) return AiOutcome.Later
            val readBack = index in kept && !remake
            val output = if (detailBytes > 0 && !readBack) Output(index, filled(width * 2, height * 2, color)).also { outputs += it } else null
            return AiOutcome.Done(filled(width, height, color), stored = readBack, detail = output?.let(::ProcessedDetail))
        }
    }

    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    /** The detail budget the adapter reads from the device's RAM. */
    private val budget: Long
        get() = DetailStore.budget(ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.totalMem)

    private fun RecyclerView.layOut(w: Int, h: Int) {
        measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        layout(0, 0, w, h)
    }

    private fun near(a: Int, b: Int): Boolean =
        abs(Color.red(a) - Color.red(b)) <= 2 && abs(Color.green(a) - Color.green(b)) <= 2 && abs(Color.blue(a) - Color.blue(b)) <= 2

    private fun middle(b: Bitmap?): Int? = b?.getPixel(b.width / 2, b.height / 2)

    private fun image(rv: RecyclerView, position: Int, slot: Int = 0): ZoomImageView? =
        (rv.findViewHolderForAdapterPosition(position) as? PageAdapter.PageHolder)?.images?.get(slot)

    private fun shownColor(rv: RecyclerView, position: Int, slot: Int = 0): Int? = middle((image(rv, position, slot)?.drawable as? BitmapDrawable)?.bitmap)

    /** The middle of a tile rendered for a zoom on [image], as its view asks for one. */
    private fun tileColor(image: ZoomImageView): Int {
        var tile: Bitmap? = null
        image.detailSource!!.render(Region(0.25, 0.25, 0.5, 0.5), 60, 90) { tile = it }
        idleUntil("the tile") { tile != null }
        return middle(tile)!!
    }

    private fun pass(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun reader(source: Source, enhancer: Enhancer, twoPage: Boolean = false, w: Int = 600, h: Int = 900): Pair<PageAdapter, RecyclerView> {
        val adapter = PageAdapter(
            source = source, mode = PageAdapter.Mode.PAGED, onTap = { _, _ -> }, doubleTapZoom = true, twoPage = twoPage, preload = 1,
            enhancer = enhancer
        )
        val rv = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            itemAnimator = null
            this.adapter = adapter
            layOut(w, h)
        }
        return adapter to rv
    }

    private fun turnTo(adapter: PageAdapter, rv: RecyclerView, item: Int) {
        rv.scrollToPosition(item)
        rv.layOut(600, 900)
        adapter.reading(item)
        idleUntil("item $item shown processed") { shownColor(rv, item)?.let { near(it, processed) } == true }
    }

    @Test
    fun aSettledZoomOnAProcessedPageDrawsTheModelsOutput() {
        val source = Source(4)
        val enhancer = Enhancer(processed)
        val (adapter, rv) = reader(source, enhancer)
        idleUntil("page 0 shown plain") { shownColor(rv, 0) == plain }
        adapter.reading(0)
        idleUntil("page 0 shown processed") { shownColor(rv, 0)?.let { near(it, processed) } == true }
        assertEquals("a page alone has the whole budget", budget, enhancer.asked.first { it.page == 0 }.detailBytes)

        val image = image(rv, 0)!!
        image.zoomTo(3f, 300f, 450f)
        idleUntil("the zoom's tile", step = { pass(50) }) { image.detailShown != null }
        assertTrue(near(middle(image.detailShown)!!, processed))
        assertEquals("no plain page loaded for the zoom", 0, source.detailLoads.get())
        adapter.release()
        assertTrue(enhancer.outputs.all { it.released })
    }

    @Test
    fun bothPagesOfASpreadZoomFromTheirOwnProcessedPages() {
        val source = Source(4)
        val enhancer = Enhancer(processed)
        val (adapter, rv) = reader(source, enhancer, twoPage = true, w = 1200, h = 900)
        idleUntil("both pages shown plain") { shownColor(rv, 0, 0) == plain && shownColor(rv, 0, 1) == plain }
        adapter.reading(0)
        idleUntil("both pages shown processed") {
            listOf(0, 1).all { slot -> shownColor(rv, 0, slot)?.let { near(it, processed) } == true }
        }
        for (slot in 0..1) assertTrue(near(tileColor(image(rv, 0, slot)!!), processed))
        assertEquals(0, source.detailLoads.get())
        // Each page of the spread has half the budget, as a plain page's detail.
        val shares = enhancer.asked.filter { it.page in 0..1 }.map { it.detailBytes }
        assertEquals(listOf(budget / 2, budget / 2), shares)
        assertTrue(enhancer.outputs.none { it.released })
        adapter.release()
    }

    @Test
    fun turningOnLetsGoOfTheProcessedPagesLeftBehind() {
        // RAM enough for the largest budget: room for 10 outputs, so the budget lets none go.
        shadowOf(context.getSystemService(ActivityManager::class.java)).setMemoryInfo(ActivityManager.MemoryInfo().apply { totalMem = 8L shl 30 })
        assertTrue(budget >= 10 * 1200L * 1800 * 4)
        val source = Source(8)
        val enhancer = Enhancer(processed)
        val (adapter, rv) = reader(source, enhancer)
        idleUntil("page 0 shown plain") { shownColor(rv, 0) == plain }
        adapter.reading(0)
        idleUntil("page 0 shown processed") { shownColor(rv, 0)?.let { near(it, processed) } == true }
        idleUntil("item 1 processed") { enhancer.outputs.any { it.page == 1 && !it.released } }
        fun held() = enhancer.outputs.filter { !it.released }.map { it.page }.toSet()
        for (item in 1..5) {
            // The next page put off: no new output lets go of anything, only the turn itself.
            enhancer.later = true
            turnTo(adapter, rv, item)
            assertEquals("turned to item $item", setOf(item - 1, item), held())
            enhancer.later = false
            adapter.aiWake()
            idleUntil("item ${item + 1} processed") { enhancer.outputs.any { it.page == item + 1 && !it.released } }
            // Kept: the page before, the page read and the next.
            assertEquals("at item $item", setOf(item - 1, item, item + 1), held())
        }
        assertTrue((0..3).all { p -> enhancer.outputs.filter { it.page == p }.all { it.released } })
        adapter.release()
    }

    @Test
    fun aheadDetailsStayAfterAiWorkEnds() {
        // RAM enough for the largest budget: the budget lets none go.
        shadowOf(context.getSystemService(ActivityManager::class.java)).setMemoryInfo(ActivityManager.MemoryInfo().apply { totalMem = 8L shl 30 })
        val source = Source(8)
        val enhancer = Enhancer(processed)
        val (adapter, rv) = reader(source, enhancer)
        idleUntil("page 0 shown plain") { shownColor(rv, 0) == plain }
        adapter.reading(0)
        idleUntil("item 1 processed") { enhancer.outputs.any { it.page == 1 } }
        turnTo(adapter, rv, 1)
        idleUntil("item 2 processed") { enhancer.outputs.any { it.page == 2 } }
        // Back to item 0: items 0 and 1 are done, so AI work has nothing left to do.
        turnTo(adapter, rv, 0)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(3, enhancer.outputs.size)
        // Item 2, two ahead, keeps its detail.
        assertEquals(setOf(0, 1, 2), enhancer.outputs.filter { !it.released }.map { it.page }.toSet())
        adapter.release()
    }

    @Test
    fun turningBackLetsGoOfTheProcessedPagesFarAhead() {
        shadowOf(context.getSystemService(ActivityManager::class.java)).setMemoryInfo(ActivityManager.MemoryInfo().apply { totalMem = 8L shl 30 })
        assertTrue(budget >= 10 * 1200L * 1800 * 4)
        val source = Source(8)
        val enhancer = Enhancer(processed)
        val (adapter, rv) = reader(source, enhancer)
        idleUntil("page 0 shown plain") { shownColor(rv, 0) == plain }
        adapter.reading(0)
        idleUntil("page 0 shown processed") { shownColor(rv, 0)?.let { near(it, processed) } == true }
        for (item in 1..7) turnTo(adapter, rv, item)
        idleUntil("item 7 processed") { enhancer.outputs.any { it.page == 7 } }
        for (item in 6 downTo 0) {
            turnTo(adapter, rv, item)
            shadowOf(Looper.getMainLooper()).idle()
            val far = enhancer.outputs.filter { it.page > item + AiLead.MAX + 1 || it.page < item - 1 }
            assertTrue("at item $item, held far: ${far.filter { !it.released }.map { it.page }}", far.all { it.released })
        }
        // By the end, items 6 and 7 were among those far ahead.
        assertTrue(enhancer.outputs.any { it.page == 6 } && enhancer.outputs.any { it.page == 7 })
        adapter.release()
    }

    @Test
    fun aLetGoProcessedPageZoomsPlainUntilMadeAgainThenTheTileIsRedrawn() {
        val source = Source(4)
        val enhancer = Enhancer(processed)
        val (adapter, rv) = reader(source, enhancer)
        idleUntil("page 0 shown plain") { shownColor(rv, 0) == plain }
        adapter.reading(0)
        idleUntil("page 0 shown processed") { shownColor(rv, 0)?.let { near(it, processed) } == true }
        adapter.trimMemory()
        assertTrue(enhancer.outputs.single { it.page == 0 }.released)

        // Put off for now: the zoom shows the plain page meanwhile, and asks for the processed one again.
        enhancer.later = true
        val image = image(rv, 0)!!
        image.zoomTo(3f, 300f, 450f)
        idleUntil("the zoom's tile", step = { pass(50) }) { image.detailShown != null }
        assertTrue(near(middle(image.detailShown)!!, plain))
        assertEquals(1, source.detailLoads.get())
        assertEquals(Ask(0, remake = true, detailBytes = budget), enhancer.asked.last())

        enhancer.later = false
        adapter.aiWake()
        idleUntil("the tile drawn from the page made again", step = { pass(50) }) {
            middle(image.detailShown)?.let { near(it, processed) } == true
        }
        assertEquals(2, enhancer.outputs.count { it.page == 0 })
        assertEquals("loaded once, for the fallback", 1, source.detailLoads.get())
        // Made once: further zooms draw from it.
        val asks = enhancer.asked.size
        assertTrue(near(tileColor(image), processed))
        assertEquals(asks, enhancer.asked.size)
        adapter.release()
    }

    @Test
    fun aPageReadBackFromDiskHasItsProcessedDetailMadeForAZoom() {
        val source = Source(4)
        val enhancer = Enhancer(processed)
        enhancer.kept += 0
        val (adapter, rv) = reader(source, enhancer)
        idleUntil("page 0 shown plain") { shownColor(rv, 0) == plain }
        adapter.reading(0)
        idleUntil("page 0 shown processed") { shownColor(rv, 0)?.let { near(it, processed) } == true }
        assertTrue(enhancer.outputs.none { it.page == 0 })

        enhancer.later = true
        val image = image(rv, 0)!!
        assertTrue(near(tileColor(image), plain))
        assertTrue(enhancer.asked.last().let { it.page == 0 && it.remake })
        enhancer.later = false
        adapter.aiWake()
        idleUntil("page 0 made again") { enhancer.outputs.any { it.page == 0 } }
        assertTrue(near(tileColor(image), processed))
        adapter.release()
    }

    @Test
    fun aNewShapeLetsGoOfTheProcessedDetailsOfThePagesProcessedAnewOnly() {
        // RAM enough for the largest budget: the budget lets none go.
        shadowOf(context.getSystemService(ActivityManager::class.java)).setMemoryInfo(ActivityManager.MemoryInfo().apply { totalMem = 8L shl 30 })
        val source = Source(4)
        val enhancer = Enhancer(processed)
        val (adapter, rv) = reader(source, enhancer)
        idleUntil("page 0 shown plain") { shownColor(rv, 0) == plain }
        adapter.reading(0)
        idleUntil("pages 0 and 1 processed") { listOf(0, 1).all { p -> enhancer.outputs.any { it.page == p } } }

        // Page 0 is read otherwise now, and put off: until it is processed anew it shows and zooms plain.
        enhancer.later = true
        adapter.aiReplanned { _, index, _, _, _ -> index == 0 }
        idleUntil("page 0 shown plain again", step = { rv.layOut(600, 900) }) { shownColor(rv, 0) == plain }
        idleUntil("page 0 asked for anew") { enhancer.asked.count { it.page == 0 } == 2 }
        assertTrue("page 0's detail let go of", enhancer.outputs.single { it.page == 0 }.released)
        assertTrue(near(tileColor(image(rv, 0)!!), plain))

        enhancer.later = false
        adapter.aiWake()
        idleUntil("page 0 processed anew") { enhancer.outputs.count { it.page == 0 } == 2 }
        assertTrue(near(tileColor(image(rv, 0)!!), processed))
        shadowOf(Looper.getMainLooper()).idle()
        // Page 1, read as before, keeps its detail and is not made again.
        assertFalse("page 1's detail kept", enhancer.outputs.single { it.page == 1 }.released)
        assertEquals(1, enhancer.asked.count { it.page == 1 })
        adapter.release()
    }

    @Test
    fun aNewShapeLetsGoOfTheProcessedDetailOfAPageNotShownProcessedThatItReadsOtherwise() {
        // RAM enough for the largest budget: the budget lets none go.
        shadowOf(context.getSystemService(ActivityManager::class.java)).setMemoryInfo(ActivityManager.MemoryInfo().apply { totalMem = 8L shl 30 })
        val source = Source(4)
        val enhancer = Enhancer(processed)
        val (adapter, rv) = reader(source, enhancer)
        idleUntil("page 0 shown plain") { shownColor(rv, 0) == plain }
        adapter.reading(0)
        idleUntil("pages 0 and 1 processed") { listOf(0, 1).all { p -> enhancer.outputs.any { it.page == p } } }

        // A turned screen: page 1, off screen, is decoded afresh at the new size and put off, so it
        // shows plain there while its output, made at the old size, is still held.
        enhancer.later = true
        rv.layOut(900, 600)
        idleUntil("page 1 asked for at the new size", step = { rv.layOut(900, 600) }) { enhancer.asked.count { it.page == 1 } == 2 }
        assertFalse(enhancer.outputs.single { it.page == 1 }.released)

        // Page 1 is read otherwise now, and its new output is on disk, read back with no detail.
        enhancer.kept += 1
        adapter.aiReplanned { _, index, _, _, _ -> index == 1 }
        assertTrue("page 1's old output let go of", enhancer.outputs.single { it.page == 1 }.released)
        assertFalse("page 0's output kept", enhancer.outputs.single { it.page == 0 }.released)

        enhancer.later = false
        adapter.aiWake()
        rv.scrollToPosition(1)
        rv.layOut(900, 600)
        adapter.reading(1)
        idleUntil("page 1 shown processed", step = { rv.layOut(900, 600) }) { shownColor(rv, 1)?.let { near(it, processed) } == true }
        assertEquals("read back from disk", 1, enhancer.outputs.count { it.page == 1 })

        // Its zoom has the output made again rather than drawing the old models' output.
        enhancer.later = true
        val image = image(rv, 1)!!
        assertTrue(near(tileColor(image), plain))
        assertTrue(enhancer.asked.any { it.page == 1 && it.remake })
        enhancer.later = false
        adapter.aiWake()
        idleUntil("page 1 made again") { enhancer.outputs.count { it.page == 1 } == 2 }
        assertTrue(near(tileColor(image), processed))
        adapter.release()
    }

    @Test
    fun aNewShapeForTheSizeAPageIsRedrawnAtLetsGoOfTheDetailItWasDrawnFrom() {
        val source = Source(4)
        val enhancer = Enhancer(processed)
        val (adapter, rv) = reader(source, enhancer)
        idleUntil("page 0 shown plain") { shownColor(rv, 0) == plain }
        adapter.reading(0)
        idleUntil("page 0 shown processed") { shownColor(rv, 0)?.let { near(it, processed) } == true }
        val before = (image(rv, 0)!!.drawable as BitmapDrawable).bitmap

        // A turned screen: page 0 is drawn from its output at the new size.
        rv.layOut(900, 600)
        idleUntil("page 0 at the turned screen's size", step = { rv.layOut(900, 600) }) {
            (image(rv, 0)?.drawable as? BitmapDrawable)?.bitmap?.let { it.width != before.width } == true
        }
        assertTrue(near(shownColor(rv, 0)!!, processed))

        // Read otherwise at the new size only, not at the size its models were given: it shows plain until processed anew.
        enhancer.later = true
        adapter.aiReplanned { _, index, _, width, _ -> index == 0 && width != before.width }
        idleUntil("page 0 shown plain again", step = { rv.layOut(900, 600) }) { shownColor(rv, 0) == plain }
        assertTrue("page 0's detail let go of", enhancer.outputs.single { it.page == 0 }.released)
        adapter.release()
    }

    @Test
    fun aTurnedScreenOrPictureShowsAndZoomsTheProcessedPageWithoutAModel() {
        val source = Source(4)
        val enhancer = Enhancer(processed)
        val (adapter, rv) = reader(source, enhancer)
        idleUntil("page 0 shown plain") { shownColor(rv, 0) == plain }
        adapter.reading(0)
        idleUntil("page 0 shown processed") { shownColor(rv, 0)?.let { near(it, processed) } == true }
        idleUntil("page 1 processed") { enhancer.asked.any { it.page == 1 } }
        val before = (image(rv, 0)!!.drawable as BitmapDrawable).bitmap
        // AI stays on: what shows processed comes from the output kept, and page 0 needs no model at the new sizes.
        val asks = enhancer.asked.count { it.page == 0 }

        /** Waits until page 1 is processed at the new size: AI work has looked at the pages wanted since, page 0 first. */
        fun nextProcessedAnew(asked: Int) {
            idleUntil("page 1 processed at the new size") { enhancer.asked.count { it.page == 1 } > asked }
            shadowOf(Looper.getMainLooper()).idle()
        }

        var nextAsks = enhancer.asked.count { it.page == 1 }
        rv.layOut(900, 600)
        // Laid out again as a window would after items change.
        idleUntil("page 0 at the turned screen's size", step = { rv.layOut(900, 600) }) {
            (image(rv, 0)?.drawable as? BitmapDrawable)?.bitmap?.let { it.width != before.width || it.height != before.height } == true
        }
        assertTrue(near(shownColor(rv, 0)!!, processed))
        assertTrue(near(tileColor(image(rv, 0)!!), processed))
        nextProcessedAnew(nextAsks)
        assertEquals("page 0 at the turned screen's size", asks, enhancer.asked.count { it.page == 0 })

        nextAsks = enhancer.asked.count { it.page == 1 }
        val turned = (image(rv, 0)!!.drawable as BitmapDrawable).bitmap
        adapter.turnsImage = { true }
        rv.layOut(900, 600)
        idleUntil("page 0 at the turned picture's size", step = { rv.layOut(900, 600) }) {
            (image(rv, 0)?.drawable as? BitmapDrawable)?.bitmap?.let { it.width != turned.width || it.height != turned.height } == true
        }
        assertTrue(near(shownColor(rv, 0)!!, processed))
        assertTrue(near(tileColor(image(rv, 0)!!), processed))
        nextProcessedAnew(nextAsks)
        assertEquals("page 0 at the turned picture's size", asks, enhancer.asked.count { it.page == 0 })
        assertEquals(0, source.detailLoads.get())
        adapter.release()
    }
}
