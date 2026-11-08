package com.infinity.comicreader.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.infinity.comicreader.ai.ScoreMap
import com.infinity.comicreader.ai.TextDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/** A wraparound cover sets reading direction only when its barcode and its tallest text agree on the front. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookDirectionCoverTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    /** A 1300 x 1000 wraparound of two 650-wide pages: a white back, with an EAN-13 barcode when [barcode], beside a red front. */
    private fun book(barcode: Boolean = true): PageSource {
        val cover = Bitmap.createBitmap(1300, 1000, Bitmap.Config.ARGB_8888)
        for (y in 0 until 1000) for (x in 0 until 1300) cover.setPixel(x, y, if (x < 650) Color.WHITE else Color.RED)
        if (barcode) {
            val matrix = MultiFormatWriter().encode("9780201379624", BarcodeFormat.EAN_13, 300, 120)
            for (y in 0 until matrix.height) for (x in 0 until matrix.width) if (matrix.get(x, y)) cover.setPixel(100 + x, 800 + y, Color.BLACK)
        }
        val inner = Bitmap.createBitmap(650, 1000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        val file = Fixtures.zip(
            File(tmp.root, "wrap$barcode.cbz"),
            Fixtures.entry("00.png", png(cover)), Fixtures.entry("01.png", png(inner)), Fixtures.entry("02.png", png(inner))
        )
        return PageSources.open(file, "UTF-8", tmp.newFolder())
    }

    /** Stands in a detector seeing a title on halves whose middle is [color]; counts its calls in [calls]. */
    private fun titleOn(color: Int, calls: IntArray = IntArray(1)): (Bitmap) -> ScoreMap = { bitmap ->
        calls[0]++
        val w = bitmap.width
        val h = bitmap.height
        val titled = bitmap.getPixel(w / 2, h / 2) == color
        ScoreMap(w, h, FloatArray(w * h) { i -> if (titled && i / w in h / 10 until h / 4 && i % w in w / 10 until w * 9 / 10) 0.9f else 0f })
    }

    @Test
    fun barcodeAndTitleOnTheSameFrontSetTheDirection() {
        assertEquals(Wraparound.Half.RIGHT, book().use { BookDirection.coverFront(it, titleOn(Color.RED)) })
    }

    @Test
    fun aTitleOnTheBarcodesSideTellsNothing() {
        assertNull(book().use { BookDirection.coverFront(it, titleOn(Color.WHITE)) })
    }

    @Test
    fun aBarcodeAloneTellsNothing() {
        assertNull(book().use { BookDirection.coverFront(it, null) })
        assertNull(book().use { BookDirection.coverFront(it) { TextDetector.ABSENT } })
        // The detector did not run.
        assertNull(book().use { BookDirection.coverFront(it) { null } })
    }

    @Test
    fun aTitleAloneTellsNothingAndIsNotLookedFor() {
        val calls = IntArray(1)
        assertNull(book(barcode = false).use { BookDirection.coverFront(it, titleOn(Color.RED, calls)) })
        assertEquals(0, calls[0])
    }
}
