package minty.emanata.ai

import android.app.Application
import android.graphics.Bitmap
import minty.emanata.data.BookId
import minty.emanata.reader.Target
import minty.emanata.util.CacheFiles
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.Executor
import kotlin.random.Random

/** Processed pages on disk: read back exactly, keyed by everything that shapes them, trimmed and forgotten like previews. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProcessedStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val now = Executor { it.run() }

    private fun page(w: Int, h: Int, seed: Int = 1): Bitmap {
        val r = Random(seed)
        val px = IntArray(w * h) { 0xFF000000.toInt() or r.nextInt(1 shl 24) }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(px, 0, w, 0, 0, w, h)
            setHasAlpha(false)
        }
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private val a = BookId(0xa)
    private val b = BookId(0xb)
    private val target = Target(40, 60)
    private val chain = AiChain.signature(listOf(ModelCatalog.forStep(AiStep.UPSCALE).first()))

    private fun key(book: BookId = a, index: Int = 0, signature: String = chain, run: String = "r0") =
        ProcessedStore.keyOf("id:${book.hex}", index, "p$index.jpg", target, 40, 60, signature, run)

    @Test
    fun aPageIsReadBackExactly() {
        val store = ProcessedStore(tmp.root, PngPageCodec, now)
        val p = page(40, 60)
        assertFalse(store.has(key()))
        store.put(key(), p)
        assertTrue(store.has(key()))
        val got = store.get(key(), 40, 60)!!
        assertArrayEquals(pixels(p), pixels(got))
        assertFalse(got.hasAlpha())
        assertFalse("the page shown is never recycled", p.isRecycled)
    }

    @Test
    fun everythingShapingAPageKeysIt() {
        val base = key()
        assertNotEquals(base, key(index = 1))
        assertNotEquals(base, key(book = b))
        assertNotEquals(base, key(signature = AiChain.signature(listOf(ModelCatalog.forStep(AiStep.UPSCALE).last()))))
        assertNotEquals(base, key(run = "u50"))
        assertNotEquals(base, ProcessedStore.keyOf("id:${a.hex}", 0, "p0.jpg", target.copy(autoCrop = true), 40, 60, chain, "r0"))
        assertNotEquals(base, ProcessedStore.keyOf("id:${a.hex}", 0, "p0.jpg", target, 41, 60, chain, "r0"))
        val store = ProcessedStore(tmp.root, PngPageCodec, now)
        store.put(base, page(40, 60))
        assertNull(store.get(key(signature = "e0;"), 40, 60))
    }

    @Test
    fun aPageOfAnotherSizeIsNotTakenAndGoes() {
        val store = ProcessedStore(tmp.root, PngPageCodec, now)
        store.put(key(), page(40, 60))
        assertNull(store.get(key(), 40, 61))
        assertFalse(store.has(key()))
    }

    @Test
    fun aBooksPagesAreForgottenTogether() {
        val store = ProcessedStore(tmp.root, PngPageCodec, now)
        store.put(key(a, 0), page(40, 60))
        store.put(key(a, 1), page(40, 60))
        store.put(key(b, 0), page(40, 60))
        ProcessedStore.forget(tmp.root, a)
        assertFalse(store.has(key(a, 0)))
        assertFalse(store.has(key(a, 1)))
        assertTrue(store.has(key(b, 0)))
        ProcessedStore.clear(tmp.root)
        assertFalse(store.has(key(b, 0)))
    }

    @Test
    fun theStoreKeepsUnderItsSizeDroppingTheLeastRecentlyRead() {
        val dir = File(tmp.root, "pages")
        val store = ProcessedStore(dir, PngPageCodec, now, maxBytes = Long.MAX_VALUE)
        val keys = (0 until 3).map { key(index = it) }
        for ((i, k) in keys.withIndex()) {
            store.put(k, page(40, 60, seed = i))
            File(dir, CacheFiles.nameOf(k, k, ends = '|') + PngPageCodec.ext).setLastModified(1_000_000L + i * 1000)
        }
        // Reading the oldest makes it the most recent.
        assertTrue(store.get(keys[0], 40, 60) != null)
        val one = dir.listFiles()!!.maxOf { it.length() }
        ProcessedStore.trim(dir, maxBytes = 2 * ((one + CacheFiles.BLOCK - 1) / CacheFiles.BLOCK * CacheFiles.BLOCK))
        assertEquals(2, dir.listFiles()!!.size)
        assertTrue("the page read last stays", store.has(keys[0]))
        assertFalse("the least recently read goes", store.has(keys[1]))
    }

    @Test
    fun aPartialWriteIsNeverReadAndAStaleOneIsTrimmed() {
        val store = ProcessedStore(tmp.root, PngPageCodec, now)
        store.put(key(), page(40, 60))
        assertTrue(tmp.root.listFiles()!!.none { it.name.endsWith(".part") })
        val stale = File(tmp.root, "x.png.part").apply { writeBytes(ByteArray(10)) }
        stale.setLastModified(0)
        ProcessedStore.trim(tmp.root)
        assertFalse(stale.exists())
        assertTrue(store.has(key()))
    }

    @Test
    fun aFileThatDoesNotReadIsDropped() {
        val store = ProcessedStore(tmp.root, PngPageCodec, now)
        store.put(key(), page(40, 60))
        tmp.root.listFiles()!!.single().writeBytes(ByteArray(20))
        assertNull(store.get(key(), 40, 60))
        assertFalse(store.has(key()))
    }

    @Test
    fun theStoreTakesATenthOfTheFreeSpaceAtMost() {
        val gb = 1024L * 1024 * 1024
        assertEquals(ProcessedStore.MAX_BYTES, ProcessedStore.limit(held = 0, free = 100 * gb))
        assertEquals((0.1 * 3 * gb).toLong(), ProcessedStore.limit(held = gb, free = 2 * gb))
        val dir = tmp.newFolder("small")
        val store = ProcessedStore(dir, PngPageCodec, now)
        store.put(key(), page(40, 60))
        store.put(key(index = 1), page(40, 60, seed = 2))
        ProcessedStore.trim(dir, free = 100L * 1024 * 1024)
        assertEquals("room enough", 2, dir.list()!!.size)
        // With no space free, a tenth of the two pages holds neither.
        ProcessedStore.trim(dir, free = 0)
        assertEquals(0, dir.list()!!.size)
    }

    @Test
    fun rgbIsReadFromThePageRowsAsTheyAre() {
        val p = page(70, 130)
        val rgb = JxlPageCodec.rgbOf(p)
        val px = pixels(p)
        assertEquals(70 * 130 * 3, rgb.size)
        for (i in px.indices step 37) {
            assertEquals((px[i] shr 16) and 0xFF, rgb[3 * i].toInt() and 0xFF)
            assertEquals((px[i] shr 8) and 0xFF, rgb[3 * i + 1].toInt() and 0xFF)
            assertEquals(px[i] and 0xFF, rgb[3 * i + 2].toInt() and 0xFF)
        }
    }
}
