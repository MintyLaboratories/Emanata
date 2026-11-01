package com.infinity.comicreader.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TilePlanTest {

    @Test
    fun marginCoversTrimAndContext() {
        assertEquals(5, Geometry(2, 0, 5).margin)
        assertEquals(4, Geometry(2, 8, 3).margin)
        assertEquals(5, Geometry(2, 9, 3).margin)
        assertEquals(7, Geometry(1, 7, 0).margin)
        assertEquals(0, Geometry(4, 0, 0).margin)
    }

    @Test
    fun badGeometryIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { Geometry(0, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { Geometry(2, -1, 0) }
        assertThrows(IllegalArgumentException::class.java) { Geometry(2, 0, -1) }
    }

    @Test
    fun startsCoverTheAxisWithTheLastAtItsEnd() {
        assertArrayEquals(intArrayOf(0), TilePlan.starts(50, 100, align = 1))
        assertArrayEquals(intArrayOf(0), TilePlan.starts(100, 100, align = 1))
        assertArrayEquals(intArrayOf(0, 100), TilePlan.starts(200, 100, align = 1))
        assertArrayEquals(intArrayOf(0, 100, 150), TilePlan.starts(250, 100, align = 1))
        assertArrayEquals(intArrayOf(0, 1), TilePlan.starts(101, 100, align = 1))
    }

    @Test
    fun alignedStartsEndTheLastCoreUpToAPeriodPastTheAxis() {
        assertArrayEquals(intArrayOf(0, 96, 160), TilePlan.starts(250, 96))
        assertArrayEquals(intArrayOf(0, 8), TilePlan.starts(97, 96))
        assertArrayEquals(intArrayOf(0, 96), TilePlan.starts(192, 96))
        assertThrows(IllegalArgumentException::class.java) { TilePlan.starts(250, 100) }
    }

    @Test
    fun everyCoreStartsInsideAndTheyCoverEveryPixel() {
        for (length in 1..400 step 7) for (core in listOf(16, 48, 96, 128)) {
            val starts = TilePlan.starts(length, core)
            val seen = BooleanArray(length)
            for (s in starts) {
                assertTrue(s in 0 until length)
                assertEquals(0, s % TilePlan.ALIGN)
                for (p in s until minOf(length, s + core)) seen[p] = true
            }
            assertTrue("gap at length $length core $core", seen.all { it })
            if (length >= core) assertTrue(starts.last() + core - length in 0 until TilePlan.ALIGN)
        }
    }

    @Test
    fun coresAreWholePeriodsAndEveryInputStartsInStep() {
        val g = Geometry(2, 0, 7)
        assertEquals(176, TilePlan.core(192, g))
        assertEquals(-2, TilePlan.core(12, g))
        for (t in TilePlan.tiles(1000, 700, 192, g)) {
            assertEquals(Math.floorMod(-g.margin, TilePlan.ALIGN), Math.floorMod(t.input.x, TilePlan.ALIGN))
            assertEquals(Math.floorMod(-g.margin, TilePlan.ALIGN), Math.floorMod(t.input.y, TilePlan.ALIGN))
        }
    }

    @Test
    fun tilesAreFixedSizeAndWriteInsideTheOutput() {
        for (g in listOf(Geometry(2, 0, 6), Geometry(2, 14, 5), Geometry(1, 0, 3), Geometry(3, 6, 4))) {
            for ((w, h) in listOf(1 to 1, 37 to 91, 128 to 128, 300 to 200, 1000 to 1500)) {
                val size = 96
                val out = TilePlan.outputSize(size, g)
                val covered = BooleanArray(w * g.scale * (h * g.scale))
                for (t in TilePlan.tiles(w, h, size, g)) {
                    assertEquals(size, t.input.width)
                    assertEquals(size, t.input.height)
                    assertTrue("crop $t past a $out tile output", t.crop.x >= 0 && t.crop.x + t.crop.width <= out)
                    assertTrue(t.crop.y >= 0 && t.crop.y + t.crop.height <= out)
                    assertTrue(t.dx + t.crop.width <= w * g.scale && t.dy + t.crop.height <= h * g.scale)
                    // The kept window maps back to the tile's input at the margin: it saw its whole context.
                    assertEquals((g.margin * g.scale - g.trim), t.crop.x)
                    assertEquals(t.input.x + g.margin, t.dx / g.scale)
                    for (y in 0 until t.crop.height) for (x in 0 until t.crop.width) {
                        covered[(t.dy + y) * w * g.scale + t.dx + x] = true
                    }
                }
                assertTrue("uncovered output for $g at ${w}x$h", covered.all { it })
            }
        }
    }

    @Test
    fun tilesRunInRowsTopToBottom() {
        val tiles = TilePlan.tiles(300, 200, 64, Geometry(2, 0, 8))
        val ys = tiles.map { it.dy }
        assertEquals(ys.sorted(), ys)
        val firstRow = tiles.takeWhile { it.dy == 0 }.map { it.dx }
        assertEquals(firstRow.sorted(), firstRow)
        assertEquals(7 * 5, tiles.size)
    }

    @Test
    fun aTileWithoutRoomForItsCoreIsRefused() {
        val g = Geometry(2, 0, 30)
        assertFalse(TilePlan.fits(64, g))
        assertTrue(TilePlan.fits(76, g))
        assertThrows(IllegalArgumentException::class.java) { TilePlan.tiles(100, 100, 64, g) }
    }
}
