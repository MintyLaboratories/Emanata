package com.infinity.comicreader.reader

import android.app.Application
import android.graphics.Bitmap
import com.infinity.comicreader.data.ReadingDirection
import com.infinity.comicreader.reader.DirectionDetection.Detected
import com.infinity.comicreader.reader.DirectionDetection.Source
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

/** Slices of one strip in a real archive: the shape from headers, the seams from page edges. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookDirectionSlicesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val width = 240

    private fun png(pixels: IntArray): ByteArray {
        val bitmap = Bitmap.createBitmap(pixels, width, pixels.size / width, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun book(pages: List<ByteArray>): PageSource {
        val file = Fixtures.zip(File(tmp.root, "book${pages.hashCode()}.cbz"), *pages.mapIndexed { i, p -> Fixtures.entry("p%02d.png".format(i), p) }.toTypedArray())
        return PageSources.open(file, "UTF-8", tmp.newFolder())
    }

    private fun detect(src: PageSource, known: Array<IntArray?>? = null) =
        src.use { BookDirection.slices(it, BookDirection.sliceSizes(it, known, read = false)) }

    @Test
    fun slicesOfVaryingHeightsTellByTheirShape() {
        val pages = listOf(600, 760, 520, 900, 640).map { TestImages.png(width, it) }
        assertEquals(Detected(ReadingDirection.TTB, Source.SHAPE), detect(book(pages)))
    }

    @Test
    fun slicesOfOneHeightTellByTheirSeams() {
        val heights = List(7) { 720 }
        val strip = SyntheticStrips.strip(width, heights.sum(), seed = 31)
        val pages = SyntheticStrips.cut(strip, width, heights).map { png(it) }
        assertEquals(Detected(ReadingDirection.TTB, Source.SEAMS), detect(book(pages)))
    }

    @Test
    fun tallPagesThatDoNotRunOnAreNotSlices() {
        val pages = List(7) { png(SyntheticStrips.strip(width, 720, seed = 50L + it)) }
        assertNull(detect(book(pages)))
    }

    @Test
    fun ordinaryPagesAndSpreadsAreNotSlices() {
        assertNull(detect(book(List(6) { TestImages.png(200, 300) })))
        assertNull(detect(book(listOf(TestImages.png(200, 300), TestImages.png(400, 300), TestImages.png(200, 300), TestImages.png(400, 300)))))
    }

    @Test
    fun knownSizesAreTakenAsTheyAre() {
        // Headers say ordinary pages; sizes already known say slices.
        val known = arrayOf<IntArray?>(intArrayOf(100, 300), intArrayOf(100, 380), intArrayOf(100, 260), intArrayOf(100, 450))
        val src = book(List(4) { TestImages.png(200, 300) })
        assertEquals(Detected(ReadingDirection.TTB, Source.SHAPE), src.use { BookDirection.slices(it, BookDirection.sliceSizes(it, known, read = true)) })
    }
}
