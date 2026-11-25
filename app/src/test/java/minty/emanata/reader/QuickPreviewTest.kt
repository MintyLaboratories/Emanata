package minty.emanata.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import minty.emanata.reader.Fixtures.entry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlin.math.abs

/** [Decoders.preview] and the sources' quick previews, on the real platform decoders. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuickPreviewTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val color = Color.rgb(40, 120, 200)

    private fun encoded(w: Int, h: Int, format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(format, 90, it) }.toByteArray()
    }

    private fun assertPreview(bitmap: Bitmap?, w: Int, h: Int) {
        assertNotNull(bitmap)
        assertArrayEquals(intArrayOf(w, h), intArrayOf(bitmap!!.width, bitmap.height))
        val c = bitmap.getPixel(w / 2, h / 2)
        assertTrue("color ${Integer.toHexString(c)}", abs(Color.red(c) - 40) < 10 && abs(Color.blue(c) - 200) < 10)
    }

    @Test
    fun jpegPreviewAtPreviewSize() = assertPreview(Decoders.preview(encoded(1600, 2400, Bitmap.CompressFormat.JPEG)), 171, 256)

    @Test
    fun webpPreviewAtPreviewSize() = assertPreview(Decoders.preview(encoded(800, 1200, Bitmap.CompressFormat.WEBP_LOSSY)), 171, 256)

    @Test
    fun smallJpegIsNotEnlarged() = assertPreview(Decoders.preview(encoded(100, 150, Bitmap.CompressFormat.JPEG)), 100, 150)

    @Test
    @Config(sdk = [27])
    fun legacyJpegPreview() = assertPreview(Decoders.preview(encoded(1600, 2400, Bitmap.CompressFormat.JPEG)), 171, 256)

    @Test
    fun pngHasNoQuickPreview() = assertNull(Decoders.preview(encoded(400, 600, Bitmap.CompressFormat.PNG)))

    @Test
    fun quickBytesStopsAfterHeaderOfSlowFormats() {
        val png = encoded(400, 600, Bitmap.CompressFormat.PNG)
        val input = ByteArrayInputStream(png)
        assertNull(Decoders.quickBytes(input))
        assertEquals(png.size - ImageKind.HEADER_BYTES, input.available())
        val jpeg = encoded(40, 60, Bitmap.CompressFormat.JPEG)
        assertArrayEquals(jpeg, Decoders.quickBytes(ByteArrayInputStream(jpeg)))
    }

    private fun avif(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("avif/$name")!!.use { it.readBytes() }

    @Test
    fun avifQuickBytesStopAtTheFirstLayer() {
        val layered = avif("layered.avif")
        val input = ByteArrayInputStream(layered)
        val bytes = Decoders.quickBytes(input)
        // ftyp and meta, then the 1388-byte first layer from 299 on (AvifPreviewTest).
        assertEquals(299 + 1388, bytes!!.size)
        assertEquals(layered.size - bytes.size, input.available())
    }

    @Test
    fun avifWithoutPreviewIsReadOnlyToItsMetadata() {
        val single = avif("single.avif")
        val input = ByteArrayInputStream(single)
        assertNull(Decoders.quickBytes(input))
        // ftyp (32) and meta (235).
        assertEquals(single.size - 267, input.available())
    }

    @Test
    @Config(sdk = [27])
    fun avifQuickBytesOnEveryApiLevel() {
        assertEquals(299 + 1388, Decoders.quickBytes(ByteArrayInputStream(avif("layered.avif")))!!.size)
    }

    @Test
    fun archivePagesPreviewByFormat() {
        val f = Fixtures.zip(
            File(temp.root, "book.cbz"),
            entry("01.jpg", encoded(1600, 2400, Bitmap.CompressFormat.JPEG)),
            entry("02.png", encoded(400, 600, Bitmap.CompressFormat.PNG))
        )
        ArchiveSource(ArchiveReader.open(f, "UTF-8")!!).use { src ->
            assertPreview(src.loadPreview(0), 171, 256)
            assertNull(src.loadPreview(1))
            assertNull(src.loadPreview(2))
        }
    }

    /** Counts the entries read. */
    private class CountingReader(private val inner: ArchiveReader) : ArchiveReader by inner {
        val read = ArrayList<String>()
        override fun <T> read(entry: ArchiveEntry, block: (InputStream) -> T): T {
            synchronized(read) { read += entry.path }
            return inner.read(entry, block)
        }
    }

    @Test
    fun pagesWithoutQuickFormatAreNeverRead() {
        val f = Fixtures.zip(
            File(temp.root, "book.cbz"),
            entry("01.png", encoded(400, 600, Bitmap.CompressFormat.PNG)),
            entry("02.jpg", encoded(400, 600, Bitmap.CompressFormat.PNG)),
            entry("03.jpg", encoded(400, 600, Bitmap.CompressFormat.JPEG))
        )
        val reader = CountingReader(ArchiveReader.open(f, "UTF-8")!!)
        ArchiveSource(reader).use { src ->
            assertNull(src.loadPreview(0))
            // Named JPEG, holds PNG: read once, then JPEG names are not read again in this book.
            assertNull(src.loadPreview(1))
            assertNull(src.loadPreview(2))
        }
        assertEquals(listOf("02.jpg"), reader.read)
    }

    @Test
    fun closedGuardedSourceHasNoPreview() {
        val f = Fixtures.zip(File(temp.root, "book.cbz"), entry("01.jpg", encoded(400, 600, Bitmap.CompressFormat.JPEG)))
        val guarded = GuardedSource(ArchiveSource(ArchiveReader.open(f, "UTF-8")!!))
        assertNotNull(guarded.loadPreview(0))
        guarded.close()
        assertNull(guarded.loadPreview(0))
    }
}
