package com.infinity.comicreader.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class EndCardTest {

    @Test
    fun centeredAtThreeQuartersOfTheHeight() {
        // 2960 * 0.75 = 2220, less half of 600.
        assertEquals(1920, EndCard.top(parentHeight = 2960, cardHeight = 600, margin = 64))
    }

    @Test
    fun keepsClearOfTheBottomBar() {
        // Centered it would end at 1386 + 300 = 1686, past the bar at 1600.
        assertEquals(1600 - 64 - 600, EndCard.top(parentHeight = 1848, cardHeight = 600, margin = 64, bottomLimit = 1600))
    }

    @Test
    fun staysOnScreenWhenTooTallToMove() {
        assertEquals(64, EndCard.top(parentHeight = 700, cardHeight = 600, margin = 64))
    }
}
