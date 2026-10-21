package com.infinity.comicreader.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import com.infinity.comicreader.reader.Fixtures.entry
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

    @Test
    fun closedGuardedSourceHasNoPreview() {
        val f = Fixtures.zip(File(temp.root, "book.cbz"), entry("01.jpg", encoded(400, 600, Bitmap.CompressFormat.JPEG)))
        val guarded = GuardedSource(ArchiveSource(ArchiveReader.open(f, "UTF-8")!!))
        assertNotNull(guarded.loadPreview(0))
        guarded.close()
        assertNull(guarded.loadPreview(0))
    }
}
