package com.infinity.comicreader.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DetailTileTest {

    private fun assertRegion(tile: DetailTile, x: Double, y: Double, w: Double, h: Double) {
        assertEquals(x, tile.region.x, 1e-9)
        assertEquals(y, tile.region.y, 1e-9)
        assertEquals(w, tile.region.width, 1e-9)
        assertEquals(h, tile.region.height, 1e-9)
    }

    @Test
    fun zoomedPageFillingTheViewTakesTheWholeView() {
        // A 1848 x 2772 page at 3x, panned 1000 px right and 2000 px down into it.
        val tile = DetailTile.of(3f, 3f, -1000f, -2000f, 1848, 2772, 1848, 2960)!!
        assertEquals(DetailTile(0, 0, 1848, 2960, tile.region), tile)
        assertRegion(tile, 1000.0 / 5544, 2000.0 / 8316, 1848.0 / 5544, 2960.0 / 8316)
    }

    @Test
    fun narrowPageTakesThePixelsWhoseCentersItCovers() {
        // 1000 px of page from 424.3: pixel 424's center (424.5) is on it, 1424's is not.
        val tile = DetailTile.of(2f, 2f, 424.3f, -100f, 500, 2000, 1848, 2960)!!
        assertEquals(424, tile.left)
        assertEquals(1000, tile.width)
        assertEquals(0, tile.top)
        assertEquals(2960, tile.height)
        assertRegion(tile, (424 - 424.3f.toDouble()) / 1000, 100.0 / 4000, 1000.0 / 1000, 2960.0 / 4000)
    }

    @Test
    fun pixelWhoseCenterIsOffThePageIsLeftOut() {
        // Left edge at 424.6 leaves pixel 424 (center 424.5) out; the right edge at 1424.6 takes 1424 in.
        val tile = DetailTile.of(2f, 2f, 424.6f, 0f, 500, 2000, 1848, 2960)!!
        assertEquals(425, tile.left)
        assertEquals(1000, tile.width)
    }

    @Test
    fun pageOffScreenHasNoTile() {
        assertNull(DetailTile.of(2f, 2f, 2000f, 0f, 500, 2000, 1848, 2960))
        assertNull(DetailTile.of(2f, 2f, 0f, 0f, 0, 2000, 1848, 2960))
    }
}
