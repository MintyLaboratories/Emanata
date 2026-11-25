package minty.emanata.util

import minty.emanata.reader.SyntheticArt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PageHashTest {

    private fun art(seed: Long, w: Int = 120, h: Int = 180) = SyntheticArt.art(w, h, seed)

    /** [pixels] with every channel moved up to [steps] at random, as lossy re-encoding does. */
    private fun noisy(pixels: IntArray, steps: Int, seed: Int): IntArray {
        val r = Random(seed)
        fun ch(v: Int) = (v + r.nextInt(-steps, steps + 1)).coerceIn(0, 255)
        return IntArray(pixels.size) { i ->
            val p = pixels[i]
            (0xFF shl 24) or (ch(p shr 16 and 0xFF) shl 16) or (ch(p shr 8 and 0xFF) shl 8) or ch(p and 0xFF)
        }
    }

    /** [pixels] [w] wide halved each way by averaging 2 x 2 blocks, as a smaller scan of the page. */
    private fun halved(pixels: IntArray, w: Int): IntArray {
        val h = pixels.size / w
        return IntArray((w / 2) * (h / 2)) { i ->
            val x = (i % (w / 2)) * 2
            val y = (i / (w / 2)) * 2
            var r = 0
            var g = 0
            var b = 0
            for (dy in 0..1) for (dx in 0..1) {
                val p = pixels[(y + dy) * w + x + dx]
                r += p shr 16 and 0xFF
                g += p shr 8 and 0xFF
                b += p and 0xFF
            }
            (0xFF shl 24) or ((r / 4) shl 16) or ((g / 4) shl 8) or (b / 4)
        }
    }

    @Test
    fun reEncodingNoiseAndSizeBarelyChangeTheHash() {
        val page = art(1)
        val hash = PageHash.of(page, 120, 180)
        assertFalse(hash.flat)
        assertTrue(hash.distance(PageHash.of(noisy(page, 6, 2), 120, 180)) <= 4)
        assertTrue(hash.distance(PageHash.of(halved(page, 120), 60, 90)) <= 4)
    }

    @Test
    fun otherPagesHashFarApart() {
        val hashes = (1L..8L).map { PageHash.of(art(it * 10), 120, 180) }
        for (i in hashes.indices) for (j in i + 1 until hashes.size) {
            assertTrue("$i-$j: ${hashes[i].distance(hashes[j])}", hashes[i].distance(hashes[j]) > SimilarBooks.MAX_DISTANCE)
        }
    }

    @Test
    fun aBlankPageIsFlatAndKeepsNoBitsFromNoise() {
        val blank = noisy(IntArray(100 * 150) { 0xFFF0F0F0.toInt() }, 1, 3)
        val hash = PageHash.of(blank, 100, 150)
        assertTrue(hash.flat)
        assertEquals(0L, hash.across)
        assertEquals(0L, hash.down)
    }

    @Test
    fun aPictureSmallerThanTheGridStillHashes() {
        // Dark left half, light right half: every row of cells grows brighter once, left to right.
        val pixels = IntArray(4 * 3) { if (it % 4 < 2) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val hash = PageHash.of(pixels, 4, 3)
        assertFalse(hash.flat)
        assertTrue(hash.across != 0L)
        assertEquals(0L, hash.down)
    }

    @Test
    fun encodesAndDecodes() {
        val hash = PageHash(-1L, 0x0123456789abcdefL, flat = false)
        assertEquals(hash, PageHash.decode(hash.encode()))
        val flat = PageHash(0L, 5L, flat = true)
        assertEquals(flat, PageHash.decode(flat.encode()))
        assertNull(PageHash.decode("12.34"))
        assertNull(PageHash.decode("zzzzzzzzzzzzzzzz.0000000000000000"))
    }
}
