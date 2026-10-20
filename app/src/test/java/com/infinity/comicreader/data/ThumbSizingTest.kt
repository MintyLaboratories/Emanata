package com.infinity.comicreader.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbSizingTest {

    @Test
    fun tierRoundsUp() {
        assertEquals(384, ThumbSizing.tier(308))
        assertEquals(384, ThumbSizing.tier(336))
        assertEquals(256, ThumbSizing.tier(256))
    }

    @Test
    fun tierStaysInRange() {
        assertEquals(96, ThumbSizing.tier(0))
        assertEquals(2048, ThumbSizing.tier(2700))
    }

    @Test
    fun nearbyCellsShareTier() {
        assertEquals(ThumbSizing.tier(700), ThumbSizing.tier(760))
    }

    @Test
    fun coverScaleFillsBothSides() {
        // A 2:3 page in a tall cell is limited by the height.
        assertEquals(0.25f, ThumbSizing.coverScale(2000, 3000, 384, 750), 1e-6f)
        // In a wide cell, by the width.
        assertEquals(0.5f, ThumbSizing.coverScale(2000, 3000, 1000, 384), 1e-6f)
    }

    @Test
    fun coverScaleNeverEnlarges() {
        assertEquals(1f, ThumbSizing.coverScale(200, 300, 384, 768), 0f)
    }

    @Test
    fun coverScaleIgnoresEmptyImage() {
        assertEquals(1f, ThumbSizing.coverScale(0, 0, 384, 384), 0f)
    }
}
