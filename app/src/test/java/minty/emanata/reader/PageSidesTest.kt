package minty.emanata.reader

import android.app.Application
import android.graphics.Bitmap
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32

/** A page decoded for the reader gives its sides as a seam check would find them, with no second read or decode. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageSidesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val width = 1200
    private val height = 1800

    private fun png(pixels: IntArray, w: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(pixels, w, pixels.size / w, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** The two halves of one picture, as PNG pages. */
    private fun spread(seed: Long): Pair<ByteArray, ByteArray> {
        val art = SyntheticArt.art(width, height, seed)
        return png(SyntheticArt.columns(art, width, 0, width / 2), width / 2) to png(SyntheticArt.columns(art, width, width / 2, width), width / 2)
    }

    @Test
    fun boxDownAveragesEachBlock() {
        val px = intArrayOf(
            0xFF000000.toInt(), 0xFF040404.toInt(), 0xFF101010.toInt(), 0xFF101010.toInt(),
            0xFF080808.toInt(), 0xFF0C0C0C.toInt(), 0xFF101010.toInt(), 0xFF101010.toInt()
        )
        assertArrayEquals(intArrayOf(0xFF060606.toInt(), 0xFF101010.toInt()), Decoders.boxDown(px, 4, 2, 2))
        assertEquals(px, Decoders.boxDown(px, 4, 2, 1))
    }

    private fun decodedSides(page: ByteArray) = Decoders.sidesOf(Decoders.decode(page, Target()))!!

    @Test
    fun sidesOfADecodedPageAgreeWithThoseOfItsBytes() {
        val spreads = (1L..4L).map { spread(it) }
        for ((i, a) in spreads.withIndex()) {
            for ((j, b) in spreads.withIndex()) {
                // The halves of one picture, and the left half of one before the right of another.
                val fromBytes = SpreadSeams.verdict(Decoders.sides(a.first)!!, Decoders.sides(b.second)!!, rtl = false)
                val fromPages = SpreadSeams.verdict(decodedSides(a.first), decodedSides(b.second), rtl = false)
                // Never the opposite of what the page's bytes tell, and right where those cannot tell.
                if (fromBytes.seam != SpreadSeams.Seam.UNKNOWN) assertEquals("$i $j: $fromBytes vs $fromPages", fromBytes.match, fromPages.match)
                assertEquals("$i $j: $fromPages", i == j, fromPages.match)
            }
        }
    }

    /**
     * [png] with an empty eXIf chunk before its pixels: refused a region decode, its sides come
     * from a whole decode, as those of formats with no region decode (JPEG XL, AVIF) do.
     */
    private fun withExif(png: ByteArray): ByteArray {
        val data = byteArrayOf(0x4D, 0x4D, 0, 0x2A, 0, 0, 0, 8, 0, 0, 0, 0, 0, 0)
        val type = "eXIf".toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(type); update(data) }.value
        val chunk = ByteBuffer.allocate(12 + data.size).putInt(data.size).put(type).put(data).putInt(crc.toInt()).array()
        // After the signature and the IHDR chunk.
        val at = 8 + 8 + 13 + 4
        return png.copyOfRange(0, at) + chunk + png.copyOfRange(at, png.size)
    }

    @Test
    fun sidesOfADecodedPageAgreeWithThoseOfAWholeDecode() {
        val spreads = (1L..4L).map { spread(it) }
        assertTrue(ImageHeaders.pngMayHaveExif(withExif(spreads[0].first)))
        for ((i, a) in spreads.withIndex()) {
            for ((j, b) in spreads.withIndex()) {
                // One side from the reader's decode, the other read alone: a seam checked as the reader moves on.
                val leftDecoded = SpreadSeams.verdict(decodedSides(a.first), Decoders.sides(withExif(b.second))!!, rtl = false)
                val rightDecoded = SpreadSeams.verdict(Decoders.sides(withExif(a.first))!!, decodedSides(b.second), rtl = false)
                assertEquals("$i $j: $leftDecoded", i == j, leftDecoded.match)
                assertEquals("$i $j: $rightDecoded", i == j, rightDecoded.match)
            }
        }
    }

    @Test
    fun aLoadedPageGivesItsSidesWithoutAnotherRead() {
        val (left, right) = spread(5)
        val book = Fixtures.zip(File(tmp.root, "book.cbz"), entry("01.png", left), entry("02.png", right))
        val inner = ArchiveReader.open(book, "UTF-8")!!
        var reads = 0
        val counting = object : ArchiveReader by inner {
            override fun <T> read(entry: ArchiveEntry, block: (InputStream) -> T): T {
                reads++
                return inner.read(entry, block)
            }
        }
        ArchiveSource(counting).use { src ->
            assertNull(src.decodedSides(0))
            src.loadPage(0, Target(600, 900)).recycle()
            assertNotNull(src.decodedSides(0))
            assertNull(src.decodedSides(1))
            assertEquals(1, reads)
            assertNotNull(src.sides(0))
            assertEquals(1, reads)
            // A page never loaded is read for them.
            assertNotNull(src.sides(1))
            assertEquals(2, reads)
        }
    }
}
