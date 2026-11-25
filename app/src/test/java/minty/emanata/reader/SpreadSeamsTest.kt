package minty.emanata.reader

import minty.emanata.reader.SpreadSeams.Seam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpreadSeamsTest {

    private val NONE = 0

    private val width = 400
    private val height = 600
    private val half = width / 2

    /** The left and right halves of a picture [width] wide. */
    private fun halves(pixels: IntArray, w: Int = width) =
        SyntheticArt.columns(pixels, w, 0, w / 2) to SyntheticArt.columns(pixels, w, w / 2, w)

    private fun sides(page: IntArray, w: Int = half) = SpreadSeams.sides(page, w, page.size / w)!!

    /** The seam between [first] and [second], read in that order. */
    private fun seam(first: IntArray, second: IntArray, rtl: Boolean = false) = SpreadSeams.verdict(sides(first), sides(second), rtl)

    @Test
    fun theHalvesOfOnePictureMatch() {
        for (seed in 1L..8L) {
            val (left, right) = halves(SyntheticArt.art(width, height, seed))
            val v = seam(left, right)
            assertEquals("seed $seed: $v", Seam.MATCH, v.seam)
            assertTrue("seed $seed: $v", v.confidence > 0.4)
        }
    }

    @Test
    fun cleanArtMatchesStrongly() {
        for (seed in 1L..3L) {
            val (left, right) = halves(SyntheticArt.art(width, height, seed))
            val v = seam(left, right)
            assertTrue("seed $seed: $v", v.match && v.confidence >= SpreadSeams.STRONG)
        }
    }

    @Test
    fun unrelatedPagesDoNotMatch() {
        for (seed in 1L..8L) {
            val left = halves(SyntheticArt.art(width, height, seed)).first
            val right = halves(SyntheticArt.art(width, height, seed + 100)).second
            val v = seam(left, right)
            assertEquals("seed $seed: $v", Seam.NO_MATCH, v.seam)
            assertTrue("seed $seed: $v", v.confidence > 0.3)
        }
    }

    @Test
    fun theOuterSidesOfASpreadDoNotMatch() {
        val (left, right) = halves(SyntheticArt.art(width, height, seed = 3))
        // Read the other way round: the right half first.
        assertNotEquals(Seam.MATCH, seam(right, left).seam)
    }

    @Test
    fun whiteGuttersSayNothing() {
        for (seed in 1L..4L) {
            val (left, right) = halves(SyntheticArt.art(width, height, seed))
            val white = 0xFFFFFF
            val v = seam(SyntheticArt.paint(left, half, half - 12, half, white), SyntheticArt.paint(right, half, 0, 12, white))
            assertEquals("seed $seed: $v", Seam.UNKNOWN, v.seam)
            assertEquals(0.0, v.confidence, 0.0)
        }
    }

    @Test
    fun darkGuttersWithNoiseSayNothing() {
        for (seed in 1L..4L) {
            val (left, right) = halves(SyntheticArt.art(width, height, seed))
            val v = seam(
                SyntheticArt.paint(left, half, half - 12, half, 0x101010, noise = 3, seed = seed),
                SyntheticArt.paint(right, half, 0, 12, 0x101010, noise = 3, seed = seed + 1)
            )
            assertEquals("seed $seed: $v", Seam.UNKNOWN, v.seam)
        }
    }

    @Test
    fun darkArtAgainstAWhiteGutterSaysNothing() {
        val left = halves(SyntheticArt.art(width, height, seed = 5)).first
        val right = SyntheticArt.paint(halves(SyntheticArt.art(width, height, seed = 6)).second, half, 0, 12, 0xFFFFFF)
        assertEquals(Seam.UNKNOWN, seam(left, right).seam)
    }

    @Test
    fun aLoneLineAcrossAGutterSaysNothing() {
        // White gutters but for one dark band near the bottom, as a panel border might cross both.
        val white = IntArray(half * height) { 0xFFFFFFFF.toInt() }
        val banded = IntArray(white.size) { i -> if (i / half in 560..575) 0xFF202020.toInt() else white[i] }
        assertEquals(Seam.UNKNOWN, seam(banded, banded.copyOf()).seam)
    }

    @Test
    fun aSharedSkySaysNothing() {
        // The same smooth sky on both pages, each with its own fine grain: no detail to tell.
        val (left, _) = halves(SyntheticArt.sky(width, height))
        val (_, right) = halves(SyntheticArt.sky(width, height))
        val v = seam(SyntheticArt.paint(left, half, 0, half, NONE, noise = 2, seed = 1, keep = true),
            SyntheticArt.paint(right, half, 0, half, NONE, noise = 2, seed = 2, keep = true))
        assertEquals(v.toString(), Seam.UNKNOWN, v.seam)
    }

    @Test
    fun jpegNoiseKeepsMatchesAndMismatches() {
        for (seed in 1L..6L) {
            val (left, right) = halves(SyntheticArt.art(width, height, seed))
            val other = halves(SyntheticArt.art(width, height, seed + 50)).second
            val jl = SyntheticArt.jpegLike(left, half, 60)
            val jr = SyntheticArt.jpegLike(right, half, 60)
            val jo = SyntheticArt.jpegLike(other, half, 60)
            assertEquals("seed $seed", Seam.MATCH, seam(jl, jr).seam)
            assertEquals("seed $seed", Seam.NO_MATCH, seam(jl, jo).seam)
        }
    }

    @Test
    fun rightToLeftComparesTheFirstPageLeftSide() {
        for (seed in 1L..4L) {
            val (left, right) = halves(SyntheticArt.art(width, height, seed))
            // Read right to left, the right half comes first.
            assertEquals("seed $seed", Seam.MATCH, seam(right, left, rtl = true).seam)
            assertNotEquals("seed $seed", Seam.MATCH, seam(right, left, rtl = false).seam)
            assertNotEquals("seed $seed", Seam.MATCH, seam(left, right, rtl = true).seam)
        }
    }

    @Test
    fun aSlightlyDifferentCropStillMatches() {
        val art = SyntheticArt.art(width, height + 6, seed = 7)
        val (left, right) = halves(art)
        // The right page starts a few rows lower, as a separate scan might.
        val shifted = right.copyOfRange(4 * half, 4 * half + half * height)
        val v = seam(left.copyOfRange(0, half * height), shifted)
        assertEquals(v.toString(), Seam.MATCH, v.seam)
    }

    @Test
    fun columnsLostAtTheCutMostlyStillMatch() {
        for ((lost, least) in listOf(1 to 9, 2 to 7, 3 to 4)) {
            val matched = (1L..10L).count { seed ->
                val w = width + lost
                val art = SyntheticArt.art(w, height, seed)
                // A trimmed scan: [lost] columns at the cut belong to neither page.
                val left = SyntheticArt.columns(art, w, 0, half)
                val right = SyntheticArt.columns(art, w, half + lost, w)
                seam(SyntheticArt.jpegLike(left, half, 75), SyntheticArt.jpegLike(right, half, 75)).match
            }
            assertTrue("lost $lost: $matched of 10", matched >= least)
        }
    }

    @Test
    fun narrowStripsAreEnough() {
        // Side strips as a decoder hands them over: only the outermost columns.
        val (left, right) = halves(SyntheticArt.art(width, height, seed = 2))
        val w = SpreadSeams.COLUMNS
        val l = SpreadSeams.edge(SyntheticArt.columns(left, half, half - w, half), w, height, right = true)!!
        val r = SpreadSeams.edge(SyntheticArt.columns(right, half, 0, w), w, height, right = false)!!
        assertTrue(SpreadSeams.compare(l, r).match)
        assertTrue(SpreadSeams.compare(sides(left).right, sides(right).left).match)
    }

    @Test
    fun emptyImagesHaveNoSides() {
        assertNull(SpreadSeams.sides(IntArray(0), 0, 0))
        val one = SpreadSeams.edge(IntArray(10) { 0xFF808080.toInt() }, 1, 10, right = true)!!
        assertFalse(SpreadSeams.informative(one))
    }
}
