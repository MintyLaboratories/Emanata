package minty.emanata.reader

import android.app.Application
import android.graphics.Bitmap
import minty.emanata.data.ReadingDirection
import minty.emanata.reader.DirectionDetection.Detected
import minty.emanata.reader.DirectionDetection.Source
import minty.emanata.reader.Slices.Checked
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
        assertEquals(Checked(Detected(ReadingDirection.TTB, Source.SHAPE)), detect(book(pages)))
    }

    @Test
    fun slicesOfOneHeightTellByTheirSeams() {
        val heights = List(7) { 720 }
        val strip = SyntheticStrips.strip(width, heights.sum(), seed = 31)
        val pages = SyntheticStrips.cut(strip, width, heights).map { png(it) }
        assertEquals(Checked(Detected(ReadingDirection.TTB, Source.SEAMS)), detect(book(pages)))
    }

    @Test
    fun tallPagesThatDoNotRunOnAreNotSlices() {
        val pages = List(7) { png(SyntheticStrips.strip(width, 720, seed = 50L + it)) }
        assertEquals(Checked(null), detect(book(pages)))
    }

    @Test
    fun ordinaryPagesAndSpreadsAreNotSlices() {
        assertEquals(Checked(null), detect(book(List(6) { TestImages.png(200, 300) })))
        assertEquals(Checked(null), detect(book(listOf(TestImages.png(200, 300), TestImages.png(400, 300), TestImages.png(200, 300), TestImages.png(400, 300)))))
    }

    /** Pages of [sizes] that count how many of their sizes were asked for. */
    private class Sized(private val sizes: List<IntArray>) : PageSource {
        var asked = 0
        override val pageCount = sizes.size
        override fun loadPage(index: Int, target: Target): Bitmap = throw UnsupportedOperationException()
        override fun pageSize(index: Int): IntArray = sizes[index].also { asked++ }
        override fun close() {}
    }

    @Test
    fun ordinaryPagesFirstStopTheReading() {
        val src = Sized(List(30) { intArrayOf(2000, 3000) })
        assertEquals(Slices.ORDINARY_LEAD, BookDirection.sliceSizes(src, null, read = true).size)
        assertEquals(Slices.ORDINARY_LEAD, src.asked)
        val unread = Sized(List(30) { intArrayOf(2000, 3000) })
        assertEquals(Checked(null), BookDirection.slices(unread, List(Slices.SAMPLE) { null }))
        assertEquals(Slices.ORDINARY_LEAD, unread.asked)
        // Slices behind a cover are all read.
        val sliced = Sized(listOf(intArrayOf(2000, 3000)) + List(29) { intArrayOf(800, 2400 + 100 * (it % 5)) })
        assertEquals(Slices.SAMPLE, BookDirection.sliceSizes(sliced, null, read = true).size)
    }

    @Test
    fun knownSizesAreTakenAsTheyAre() {
        // Headers say ordinary pages; sizes already known say slices.
        val known = arrayOf<IntArray?>(intArrayOf(100, 300), intArrayOf(100, 380), intArrayOf(100, 260), intArrayOf(100, 450))
        val src = book(List(4) { TestImages.png(200, 300) })
        assertEquals(Checked(Detected(ReadingDirection.TTB, Source.SHAPE)), src.use { BookDirection.slices(it, BookDirection.sliceSizes(it, known, read = true)) })
    }

    @Test
    fun sizesThatCannotBeReadLeaveItOpen() {
        val unreadable = object : PageSource {
            override val pageCount = 6
            override fun loadPage(index: Int, target: Target): Bitmap = throw UnsupportedOperationException()
            override fun pageSize(index: Int): IntArray = throw java.io.IOException("unreadable")
            override fun close() {}
        }
        assertNull(BookDirection.slices(unreadable, List(6) { null }))
        assertNull(BookDirection.slices(Sized(List(6) { intArrayOf(800, 2400 + it) }), List(6) { null }) { true })
    }

    @Test
    fun aCheckIsKeptForTheBookUntilItChanges() {
        val file = File(tmp.root, "kept.cbz").apply { writeBytes(ByteArray(10)) }
        assertNull(BookDirection.checkedSlices(file))
        BookDirection.rememberSlices(file, Checked(null))
        assertEquals(Checked(null), BookDirection.checkedSlices(file))
        file.writeBytes(ByteArray(20))
        assertNull(BookDirection.checkedSlices(file))
    }
}
