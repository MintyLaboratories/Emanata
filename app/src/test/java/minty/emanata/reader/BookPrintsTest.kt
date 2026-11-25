package minty.emanata.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import minty.emanata.testing.Waits
import minty.emanata.util.SimilarBooks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookPrintsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val cacheDir by lazy { temp.newFolder("cache") }

    private fun page(seed: Long, format: Bitmap.CompressFormat, w: Int = W, h: Int = H): ByteArray {
        val bitmap = Bitmap.createBitmap(SyntheticArt.art(w, h, seed), w, h, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bitmap.compress(format, 85, it) }.toByteArray()
    }

    private fun pages(seeds: List<Long>, format: Bitmap.CompressFormat, ext: String) =
        seeds.mapIndexed { i, seed -> Fixtures.entry("p%02d.$ext".format(i + 1), page(seed, format)) }

    private fun read(file: File) = BookPrints.read(file, "UTF-8", cacheDir)

    /** Drains the writer all preview stores share, then lists the previews it wrote. */
    private fun previews(): List<File> {
        // The one writer runs in order: a make queued now runs once the writes before it are done.
        val drained = CountDownLatch(1)
        assertTrue(PreviewStore(temp.newFolder("drain")).putMade("drain") { drained.countDown(); null })
        assertTrue("Gave up waiting for the preview writer", drained.await(Waits.LIVENESS_MS, TimeUnit.MILLISECONDS))
        return PreviewStore.dirIn(cacheDir).listFiles()?.filter { it.isFile }.orEmpty()
    }

    @Test
    fun hashReadsLeaveNoPreviewSmallerThanAPreview() {
        val book = Fixtures.zip(File(temp.root, "Tale.cbz"), *pages((1L..8L).toList(), Bitmap.CompressFormat.PNG, "png").toTypedArray())
        assertNotNull(read(book))
        for (f in previews()) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            assertTrue("${o.outWidth} x ${o.outHeight}", maxOf(o.outWidth, o.outHeight) >= Previews.BOX)
        }
    }

    @Test
    fun pageThatNeverDecodesIsKeptButFailuresThatMayPassAreNot() {
        assertTrue(BookPrints.isPermanent(DecodeException("bad")))
        assertFalse(BookPrints.isPermanent(DecodeException("busy", transient = true)))
        assertFalse(BookPrints.isPermanent(OutOfMemoryError()))
        assertFalse(BookPrints.isPermanent(IOException()))
        assertFalse(BookPrints.isPermanent(InterruptedException()))
        val entries = pages((1L..8L).toList(), Bitmap.CompressFormat.JPEG, "jpg").toMutableList()
        // The cover's neighbor, which the first place reads, is not an image.
        entries[1] = Fixtures.entry("p02.jpg", ByteArray(4096) { (it * 31).toByte() })
        val read = read(Fixtures.zip(File(temp.root, "Tale.cbz"), *entries.toTypedArray()))!!
        assertTrue(read.keep)
        assertNull(read.entry.print.slots[0][1])
    }

    @Test
    fun cbrAndCbzOfTheSamePagesAreAlike() {
        val seeds = (1L..8L).toList()
        val zip = Fixtures.zip(File(temp.root, "Tale.cbz"), *pages(seeds, Bitmap.CompressFormat.PNG, "png").toTypedArray())
        val rar = File(temp.root, "Tale.cbr").apply { writeBytes(Fixtures.rar4Bytes(*pages(seeds, Bitmap.CompressFormat.JPEG, "jpg").toTypedArray())) }
        val rar5 = File(temp.root, "Tale 5.cbr").apply { writeBytes(Fixtures.rar5Bytes(*pages(seeds, Bitmap.CompressFormat.WEBP, "webp").toTypedArray())) }
        val other = File(temp.root, "Other.cbr").apply {
            writeBytes(Fixtures.rar4Bytes(*pages((101L..108L).toList(), Bitmap.CompressFormat.JPEG, "jpg").toTypedArray()))
        }
        val a = read(zip)!!.entry
        val b = read(rar)!!.entry
        val c = read(rar5)!!.entry
        val d = read(other)!!.entry
        assertEquals("cbr", b.container)
        assertEquals("jpg", b.image)
        assertEquals(8, b.print.pages)
        assertTrue(SimilarBooks.alike(a.print, b.print))
        assertTrue(SimilarBooks.alike(b.print, c.print))
        assertFalse(SimilarBooks.alike(b.print, d.print))
    }

    private companion object {
        // Sampled by the hash's cap to 150 x 225, below a preview's size.
        const val W = 600
        const val H = 900
    }
}
